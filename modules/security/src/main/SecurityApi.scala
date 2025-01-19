package lila.security

import scala.annotation.nowarn

import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraint
import play.api.data.validation.Invalid
import play.api.data.validation.ValidationError
import play.api.data.validation.{ Valid => FormValid }
import play.api.mvc.RequestHeader

import org.joda.time.DateTime
import ornicar.scalalib.Random
import reactivemongo.api.ReadPreference
import reactivemongo.api.bson._

import lila.common.EmailAddress
import lila.common.HTTPRequest
import lila.common.IpAddress
import lila.db.dsl._
import lila.oauth.OAuthScope
import lila.oauth.OAuthServer
import lila.user.User
import lila.user.User.LoginCandidate
import lila.user.UserRepo

final class SecurityApi(
    userRepo: UserRepo,
    store: Store,
    firewall: Firewall,
    geoIP: GeoIP,
    authenticator: lila.user.Authenticator,
    emailValidator: EmailAddressValidator,
    oAuthServer: OAuthServer,
    tor: Tor,
)(implicit ec: scala.concurrent.ExecutionContext) {

  val AccessUri = "access_uri"

  lazy val usernameOrEmailForm = Form(
    single(
      "username" -> nonEmptyText,
    ),
  )

  lazy val loginForm = Form(
    tuple(
      "username" -> nonEmptyText, // can also be an email
      "password" -> nonEmptyText,
    ),
  )

  private def loadedLoginForm(candidate: Option[LoginCandidate]) =
    Form(
      mapping(
        "username" -> nonEmptyText, // can also be an email
        "password" -> nonEmptyText,
        "token"    -> optional(nonEmptyText),
      )(authenticateCandidate(candidate)) {
        case LoginCandidate.Success(user) => (user.username, "", none).some
        case _                            => none
      }.verifying(Constraint { (t: LoginCandidate.Result) =>
        t match {
          case LoginCandidate.Success(_) => FormValid
          case LoginCandidate.InvalidUsernameOrPassword =>
            Invalid(Seq(ValidationError("invalidUsernameOrPassword")))
          case err => Invalid(Seq(ValidationError(err.toString)))
        }
      }),
    )

  def loadLoginForm(str: String): Fu[Form[LoginCandidate.Result]] = {
    emailValidator.validate(EmailAddress(str)) match {
      case Some(EmailAddressValidator.Acceptable(email)) =>
        authenticator.loginCandidateByEmail(email.normalize)
      case None if User.couldBeUsername(str) => authenticator.loginCandidateById(User normalize str)
      case _                                 => fuccess(none)
    }
  } map loadedLoginForm _

  @nowarn("cat=unused")
  private def authenticateCandidate(candidate: Option[LoginCandidate])(
      _username: String,
      password: String,
      token: Option[String],
  ): LoginCandidate.Result =
    candidate.fold[LoginCandidate.Result](LoginCandidate.InvalidUsernameOrPassword) {
      _(User.PasswordAndToken(User.ClearPassword(password), token map User.TotpToken.apply))
    }

  def saveAuthentication(userId: User.ID)(implicit
      req: RequestHeader,
  ): Fu[String] =
    userRepo mustConfirmEmail userId flatMap {
      case true => fufail(SecurityApi MustConfirmEmail userId)
      case false =>
        val sessionId = Random secureString 22
        if (tor isExitNode HTTPRequest.lastRemoteAddress(req)) logger.info(s"TOR login $userId")
        store.save(sessionId, userId, req, up = true, fp = none) inject sessionId
    }

  def saveSignup(userId: User.ID, fp: Option[FingerPrint])(implicit
      req: RequestHeader,
  ): Funit = {
    val sessionId = lila.common.ThreadLocalRandom nextString 22
    store.save(s"SIG-$sessionId", userId, req, up = false, fp = fp)
  }

  def restoreUser(req: RequestHeader): Fu[Option[FingerPrintedUser]] =
    firewall.accepts(req) ?? HTTPRequest.userSessionId(req) ?? { sessionId =>
      store.authInfo(sessionId) flatMap {
        _ ?? { d =>
          userRepo byId d.user dmap { _ map { FingerPrintedUser(_, d.hasFp) } }
        }
      }
    }

  def oauthScoped(
      req: RequestHeader,
      scopes: List[lila.oauth.OAuthScope],
  ): Fu[lila.oauth.OAuthServer.AuthResult] =
    oAuthServer.auth(req, scopes) map { _ map stripRolesOfOAuthUser }

  private def stripRolesOfOAuthUser(scoped: OAuthScope.Scoped) =
    // if (scoped.scopes has OAuthScope.Web.Mod) scoped else
    scoped.copy(user = stripRolesOfUser(scoped.user))

  private lazy val nonModRoles: Set[String] = Permission.nonModPermissions.map(_.dbKey)

  private def stripRolesOfUser(user: User) =
    user.copy(roles = user.roles.filter(nonModRoles.contains))

  def locatedOpenSessions(userId: User.ID, nb: Int): Fu[List[LocatedSession]] =
    store.openSessions(userId, nb) map {
      _.map { session =>
        LocatedSession(session, geoIP(session.ip))
      }
    }

  def dedup(userId: User.ID, req: RequestHeader): Funit =
    HTTPRequest.userSessionId(req) ?? { store.dedup(userId, _) }

  def setFingerPrint(req: RequestHeader, fp: FingerPrint): Fu[Option[FingerHash]] =
    HTTPRequest.userSessionId(req) ?? { store.setFingerPrint(_, fp) map some }

  def recentUserIdsByFingerHash(fh: FingerHash) = recentUserIdsByField("fp")(fh.value)

  def recentUserIdsByIp(ip: IpAddress) = recentUserIdsByField("ip")(ip.value)

  def shareIpOrPrint(u1: User.ID, u2: User.ID): Fu[Boolean] =
    store.ipsAndFps(List(u1, u2), max = 100) map { ipsAndFps =>
      val u1s: Set[String] = ipsAndFps
        .filter(_.user == u1)
        .flatMap { x =>
          List(x.ip.value, ~x.fp)
        }
        .toSet
      ipsAndFps.exists { x =>
        x.user == u2 && {
          u1s(x.ip.value) || x.fp.??(u1s.contains)
        }
      }
    }

  def ipUas(ip: IpAddress): Fu[List[String]] =
    store.coll
      .distinctEasy[String, List]("ua", $doc("ip" -> ip.value), ReadPreference.secondaryPreferred)

  def printUas(fh: FingerHash): Fu[List[String]] =
    store.coll
      .distinctEasy[String, List]("ua", $doc("fp" -> fh.value), ReadPreference.secondaryPreferred)

  private def recentUserIdsByField(field: String)(value: String): Fu[List[User.ID]] =
    store.coll.distinctEasy[User.ID, List](
      "user",
      $doc(
        field -> value,
        "date" $gt DateTime.now.minusYears(1),
      ),
      ReadPreference.secondaryPreferred,
    )
}

object SecurityApi {

  case class MustConfirmEmail(userId: User.ID) extends Exception
}
