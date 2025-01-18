package lila.security

import scala.concurrent.duration._
import scala.util.chaining._

import play.api.data._
import play.api.i18n.Lang
import play.api.mvc.Request
import play.api.mvc.RequestHeader

import lila.common.EmailAddress
import lila.common.HTTPRequest
import lila.common.IpAddress
import lila.common.config.NetConfig
import lila.memo.RateLimit
import lila.user.PasswordHasher
import lila.user.User

final class Signup(
    store: Store,
    api: SecurityApi,
    ipTrust: IpTrust,
    forms: DataForm,
    emailAddressValidator: EmailAddressValidator,
    emailConfirm: EmailConfirm,
    recaptcha: Recaptcha,
    authenticator: lila.user.Authenticator,
    userRepo: lila.user.UserRepo,
    netConfig: NetConfig
)(implicit ec: scala.concurrent.ExecutionContext) {

  sealed abstract private class MustConfirmEmail(val value: Boolean)
  private object MustConfirmEmail {

    case object Nope                   extends MustConfirmEmail(false)
    case object YesBecausePrintExists  extends MustConfirmEmail(true)
    case object YesBecausePrintMissing extends MustConfirmEmail(true)
    case object YesBecauseIpExists     extends MustConfirmEmail(true)
    case object YesBecauseIpSusp       extends MustConfirmEmail(true)
    case object YesBecauseMobile       extends MustConfirmEmail(true)
    case object YesBecauseUA           extends MustConfirmEmail(true)

    def apply(print: Option[FingerPrint])(implicit req: RequestHeader): Fu[MustConfirmEmail] = {
      val ip = HTTPRequest lastRemoteAddress req
      store.recentByIpExists(ip) flatMap { ipExists =>
        if (ipExists) fuccess(YesBecauseIpExists)
        else if (HTTPRequest weirdUA req) fuccess(YesBecauseUA)
        else
          print.fold[Fu[MustConfirmEmail]](fuccess(YesBecausePrintMissing)) { fp =>
            store.recentByPrintExists(fp) flatMap { printFound =>
              if (printFound) fuccess(YesBecausePrintExists)
              else
                ipTrust.isSuspicious(ip).map {
                  case true => YesBecauseIpSusp
                  case _    => Nope
                }
            }
          }
      }
    }
  }

  def website(
      blind: Boolean
  )(implicit req: Request[_], lang: Lang, formBinding: FormBinding): Fu[Signup.Result] =
    forms.signup.website
      .bindFromRequest()
      .fold[Fu[Signup.Result]](
        err => fuccess(Signup.Bad(err tap signupErrLog)),
        data =>
          recaptcha.verify(~data.recaptchaResponse, req).flatMap {
            case false =>
              authLog(data.username, data.email, "Signup recaptcha fail")
              fuccess(Signup.Bad(forms.signup.website fill data))
            case true =>
              signupRateLimit(data.username, if (data.recaptchaResponse.isDefined) 1 else 2) {
                MustConfirmEmail(data.fingerPrint) flatMap { mustConfirm =>
                  lila.mon.user.register.count.increment()
                  lila.mon.user.register.mustConfirmEmail(mustConfirm.toString).increment()
                  val email = emailAddressValidator
                    .validate(data.realEmail) err s"Invalid email ${data.email}"
                  val passwordHash = authenticator passEnc User.ClearPassword(data.password)
                  userRepo
                    .create(
                      data.username,
                      passwordHash,
                      email.acceptable,
                      blind,
                      mustConfirmEmail = mustConfirm.value
                    )
                    .orFail(s"No user could be created for ${data.username}")
                    .addEffect { logSignup(_, email.acceptable, data.fingerPrint, mustConfirm) }
                    .flatMap {
                      confirmOrAllSet(email, mustConfirm, data.fingerPrint)
                    }
                }
              }
          }
      )

  private def confirmOrAllSet(
      email: EmailAddressValidator.Acceptable,
      mustConfirm: MustConfirmEmail,
      fingerPrint: Option[FingerPrint]
  )(user: User)(implicit req: RequestHeader, lang: Lang): Fu[Signup.Result] =
    store.deletePreviousSessions(user) >> {
      if (mustConfirm.value) {
        emailConfirm.send(user, email.acceptable) >> {
          if (emailConfirm.effective)
            api.saveSignup(user.id, fingerPrint) inject
              Signup.ConfirmEmail(user, email.acceptable)
          else fuccess(Signup.AllSet(user, email.acceptable))
        }
      } else fuccess(Signup.AllSet(user, email.acceptable))
    }

  def mobile(implicit req: Request[_], lang: Lang, formBinding: FormBinding): Fu[Signup.Result] =
    forms.signup.mobile
      .bindFromRequest()
      .fold[Fu[Signup.Result]](
        err => fuccess(Signup.Bad(err tap signupErrLog)),
        data =>
          signupRateLimit(data.username, cost = 2) {
            val email = emailAddressValidator
              .validate(data.realEmail) err s"Invalid email ${data.email}"
            val mustConfirm = MustConfirmEmail.YesBecauseMobile
            lila.mon.user.register.count.increment()
            lila.mon.user.register.mustConfirmEmail(mustConfirm.toString).increment()
            val passwordHash = authenticator passEnc User.ClearPassword(data.password)
            userRepo
              .create(
                data.username,
                passwordHash,
                email.acceptable,
                false,
                mustConfirmEmail = mustConfirm.value
              )
              .orFail(s"No user could be created for ${data.username}")
              .addEffect { logSignup(_, email.acceptable, none, mustConfirm) }
              .flatMap {
                confirmOrAllSet(email, mustConfirm, none)
              }
          }
      )

  private def HasherRateLimit =
    PasswordHasher.rateLimit[Signup.Result](enforce = netConfig.rateLimit) _

  private lazy val signupRateLimitPerIP = RateLimit.composite[IpAddress](
    key = "account.create.ip",
    enforce = netConfig.rateLimit.value
  )(
    ("fast", 10, 10.minutes),
    ("slow", 150, 1 day)
  )

  private val rateLimitDefault = fuccess(Signup.RateLimited)

  private def signupRateLimit(username: String, cost: Int)(
      f: => Fu[Signup.Result]
  )(implicit req: RequestHeader): Fu[Signup.Result] =
    HasherRateLimit(username, req) { _ =>
      signupRateLimitPerIP(HTTPRequest lastRemoteAddress req, cost = cost)(f)(rateLimitDefault)
    }(rateLimitDefault)

  private def logSignup(
      user: User,
      email: EmailAddress,
      fingerPrint: Option[FingerPrint],
      mustConfirm: MustConfirmEmail
  ) =
    authLog(
      user.username,
      email.value,
      s"fp: ${fingerPrint} mustConfirm: $mustConfirm fp: ${fingerPrint.??(_.value)}}"
    )

  private def signupErrLog(err: Form[_]) =
    for {
      username <- err("username").value
      email    <- err("email").value
    } {
      if (
        err.errors.exists(_.messages.contains("error.email_acceptable")) &&
        err("email").value.exists(EmailAddress.matches)
      )
        authLog(username, email, s"Signup with unacceptable email")
    }

  private def authLog(user: String, email: String, msg: String) =
    lila.log("auth").info(s"$user $email $msg")
}

object Signup {

  sealed trait Result
  case class Bad(err: Form[_])                             extends Result
  case object RateLimited                                  extends Result
  case class ConfirmEmail(user: User, email: EmailAddress) extends Result
  case class AllSet(user: User, email: EmailAddress)       extends Result
}
