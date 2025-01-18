package lila.plan

import org.joda.time.DateTime

import lila.db.dsl._
import lila.user.UserRepo

final private class Expiration(
    userRepo: UserRepo,
    patronColl: Coll,
    notifier: PlanNotifier
)(implicit ec: scala.concurrent.ExecutionContext) {

  import BsonHandlers._
  import PatronHandlers._

  def run: Funit =
    getExpired flatMap {
      _.map { patron =>
        patronColl.update.one($id(patron.id), patron.removeStripe.removePayPal) >>
          disableUserPlanOf(patron) >>-
          logger.info(s"Expired ${patron}")
      }.sequenceFu.void
    }

  private def disableUserPlanOf(patron: Patron): Funit =
    userRepo byId patron.userId flatMap {
      _ ?? { user =>
        userRepo.setPlan(user, user.plan.disable) >>
          notifier.onExpire(user)
      }
    }

  private def getExpired =
    patronColl.list[Patron](
      $doc(
        "expiresAt" $lt DateTime.now,
        "lifetime" $ne true,
        "patreon" $ne true
      ),
      50
    )
}
