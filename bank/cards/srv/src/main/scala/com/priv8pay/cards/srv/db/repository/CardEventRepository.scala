package com.priv8pay.cards.srv.db.repository

import com.priv8pay.cards.dto._
import com.priv8pay.cards.srv.db.entity.{Card, CardEventEntity, Tag}
import com.priv8pay.cards.srv.util.TagUtils.toPairs
import com.priv8pay.commons.db.repository.UidRepository
import com.priv8pay.commons.db.search.SearchUtils
import com.priv8pay.commons.dto.search.SearchRequest
import jakarta.persistence.criteria._
import org.springframework.data.domain.{Page, Pageable}
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Repository

import scala.collection.mutable
import scala.reflect.ClassTag

@Repository
trait CardEventRepository extends UidRepository[CardEventEntity, java.lang.Long] {

  def findByCard_Uid(cardUid: String): Array[CardEventEntity]
  def findByCard_Uid(cardUid: String, pageable: Pageable): Page[CardEventEntity]

  def findByAction_Uid(actionUid: String): Array[CardEventEntity]
  def findByAction_Uid(actionUid: String, pageable: Pageable): Page[CardEventEntity]

  def findByActivity_Uid(activityUid: String): Array[CardEventEntity]
  def findByActivity_Uid(activityUid: String, pageable: Pageable): Page[CardEventEntity]

  def findByActionRequest_Uid(actionRequestUid: String): Array[CardEventEntity]
  def findByActionRequest_Uid(actionRequestUid: String, pageable: Pageable): Page[CardEventEntity]

  def search(searchReq: SearchRequest[CardEventFilter]): Page[CardEventEntity] = {
    val pageRequest = SearchUtils.toPageRequest(searchReq)

    val spec: Specification[CardEventEntity] =
      (root: Root[CardEventEntity], query: CriteriaQuery[_], cb: CriteriaBuilder) => {

        query.distinct(true)

        val predicates = mutable.ArrayBuffer.empty[Predicate]
        val f = searchReq.filter

        val cardJoin =
          root.join("card", JoinType.LEFT).asInstanceOf[Join[CardEventEntity, Card]]

        val actionJoin =
          root.join("action", JoinType.LEFT)

        val activityJoin =
          root.join("activity", JoinType.LEFT)

        val actionRequestJoin =
          root.join("actionRequest", JoinType.LEFT)

        val cardholderJoin =
          cardJoin.join("cardholder", JoinType.LEFT)

        val organizationJoin =
          cardholderJoin.join("organization", JoinType.LEFT)

        def existsCardTagAny(pairs: Seq[(String, String)]): Predicate = {
          val sq = query.subquery(classOf[java.lang.Long])
          val e  = sq.from(classOf[CardEventEntity])
          val c  = e.join("card").asInstanceOf[Join[CardEventEntity, Card]]
          val t  = c.join("tags").asInstanceOf[Join[Card, Tag]]

          val orPreds: Array[Predicate] =
            pairs.map { case (k, v) =>
              val pk = cb.equal(t.get[String]("kind"), k)
              val pv = cb.equal(t.get[String]("value"), v)
              cb.and(Array(pk, pv): _*)
            }.toArray(ClassTag(classOf[Predicate]))

          val anyTag =
            if (orPreds.length == 1) orPreds(0)
            else cb.or(orPreds: _*)

          sq.select(e.get[java.lang.Long]("id"))
            .where(
              cb.equal(e.get[java.lang.Long]("id"), root.get[java.lang.Long]("id")),
              anyTag
            )

          cb.exists(sq)
        }

        // --- uids ---

        if (f.uids.nonEmpty) {
          predicates += root.get[String]("uid").in(f.uids.toSeq: _*)
        }

        // --- card/action/activity/request scopes ---

        if (f.cardUids.nonEmpty) {
          predicates += cardJoin.get[String]("uid").in(f.cardUids.toSeq: _*)
        }
        if (f.excludeCardUids.nonEmpty) {
          predicates += cb.not(cardJoin.get[String]("uid").in(f.excludeCardUids.toSeq: _*))
        }

        if (f.actionUids.nonEmpty) {
          predicates += actionJoin.get[String]("uid").in(f.actionUids.toSeq: _*)
        }
        if (f.excludeActionUids.nonEmpty) {
          predicates += cb.not(actionJoin.get[String]("uid").in(f.excludeActionUids.toSeq: _*))
        }

        if (f.activityUids.nonEmpty) {
          predicates += activityJoin.get[String]("uid").in(f.activityUids.toSeq: _*)
        }
        if (f.excludeActivityUids.nonEmpty) {
          predicates += cb.not(activityJoin.get[String]("uid").in(f.excludeActivityUids.toSeq: _*))
        }

        if (f.actionRequestUids.nonEmpty) {
          predicates += actionRequestJoin.get[String]("uid").in(f.actionRequestUids.toSeq: _*)
        }
        if (f.excludeActionRequestUids.nonEmpty) {
          predicates += cb.not(actionRequestJoin.get[String]("uid").in(f.excludeActionRequestUids.toSeq: _*))
        }

        // --- category / event type / cause ---

        if (f.categories.nonEmpty) {
          predicates += root.get[CardEventCategory]("category").in(f.categories.toSeq: _*)
        }
        if (f.excludeCategories.nonEmpty) {
          predicates += cb.not(root.get[CardEventCategory]("category").in(f.excludeCategories.toSeq: _*))
        }

        if (f.eventTypes.nonEmpty) {
          predicates += root.get[CardEventType]("eventType").in(f.eventTypes.toSeq: _*)
        }
        if (f.excludeEventTypes.nonEmpty) {
          predicates += cb.not(root.get[CardEventType]("eventType").in(f.excludeEventTypes.toSeq: _*))
        }

        if (f.webTimelineOnly) {
          def eventPair(category: CardEventCategory, eventType: CardEventType): Predicate =
            cb.and(
              cb.equal(root.get[CardEventCategory]("category"), category),
              cb.equal(root.get[CardEventType]("eventType"), eventType)
            )

          predicates += cb.or(
            eventPair(CardEventCategory.WORKFLOW, CardEventType.WORKFLOW_INITIALIZED),
            eventPair(CardEventCategory.WORKFLOW, CardEventType.ACTIVITY_APPROVED),
            eventPair(CardEventCategory.WORKFLOW, CardEventType.ACTIVITY_DENIED),
            eventPair(CardEventCategory.ACTIVITY, CardEventType.ACTIVITY_EDITED),
            eventPair(CardEventCategory.ACTION, CardEventType.ACTION_CANCELLED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_NEW_PAN),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_ISSUED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_SHIPPED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_REPLACED),
            eventPair(CardEventCategory.CARD_LIMIT, CardEventType.CARD_LIMIT_UPDATED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_ACTIVATED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_BLOCKED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_UNBLOCKED),
            eventPair(CardEventCategory.CARD_STATE, CardEventType.CARD_CLOSED)
          )
        }

        if (f.causes.nonEmpty) {
          predicates += root.get[CardEventCause]("cause").in(f.causes.toSeq: _*)
        }
        if (f.excludeCauses.nonEmpty) {
          predicates += cb.not(root.get[CardEventCause]("cause").in(f.excludeCauses.toSeq: _*))
        }

        // --- origin filters ---

        if (f.originTypes.nonEmpty) {
          predicates += root.get[CardActivity.OriginType]("originType").in(f.originTypes.toSeq: _*)
        }
        if (f.excludeOriginTypes.nonEmpty) {
          predicates += cb.not(root.get[CardActivity.OriginType]("originType").in(f.excludeOriginTypes.toSeq: _*))
        }

        if (f.originUserUids.nonEmpty) {
          predicates += root.get[String]("originUserUid").in(f.originUserUids.toSeq: _*)
        }
        if (f.excludeOriginUserUids.nonEmpty) {
          predicates += cb.not(root.get[String]("originUserUid").in(f.excludeOriginUserUids.toSeq: _*))
        }

        if (f.originAuthorities.nonEmpty) {
          predicates += root.get[Authority]("originAuthority").in(f.originAuthorities.toSeq: _*)
        }
        if (f.excludeOriginAuthorities.nonEmpty) {
          predicates += cb.not(root.get[Authority]("originAuthority").in(f.excludeOriginAuthorities.toSeq: _*))
        }

        // --- optional filter via linked action.actionType ---

        if (f.actionTypes.nonEmpty) {
          predicates += actionJoin.get[CardAction.ActionType]("actionType").in(f.actionTypes.toSeq: _*)
        }
        if (f.excludeActionTypes.nonEmpty) {
          predicates += cb.not(actionJoin.get[CardAction.ActionType]("actionType").in(f.excludeActionTypes.toSeq: _*))
        }

        // --- ACL scoping via card -> cardholder -> organization ---

        if (f.orgUids.nonEmpty) {
          predicates += organizationJoin.get[String]("uid").in(f.orgUids.toSeq: _*)
        }
        if (f.excludeOrgUids.nonEmpty) {
          predicates += cb.not(organizationJoin.get[String]("uid").in(f.excludeOrgUids.toSeq: _*))
        }

        if (f.userUids.nonEmpty) {
          predicates += cardholderJoin.get[String]("uid").in(f.userUids.toSeq: _*)
        }
        if (f.excludeUserUids.nonEmpty) {
          predicates += cb.not(cardholderJoin.get[String]("uid").in(f.excludeUserUids.toSeq: _*))
        }

        // --- dateFrom / dateTo on creationTime ---

        val dateFromJ: java.lang.Long =
          com.priv8pay.commons.utils.ConvertUtils.toJava(f.dateFrom)
        Option(dateFromJ).foreach { v =>
          predicates += cb.greaterThanOrEqualTo(root.get[java.lang.Long]("creationTime"), v)
        }

        val dateToJ: java.lang.Long =
          com.priv8pay.commons.utils.ConvertUtils.toJava(f.dateTo)
        Option(dateToJ).foreach { v =>
          predicates += cb.lessThanOrEqualTo(root.get[java.lang.Long]("creationTime"), v)
        }

        // --- tags via event.card.tags ---

        val includePairs = toPairs(f.tags)
        if (includePairs.nonEmpty) {
          predicates += existsCardTagAny(includePairs)
        }

        val excludePairs = toPairs(f.excludeTags)
        if (excludePairs.nonEmpty) {
          predicates += cb.not(existsCardTagAny(excludePairs))
        }

        // --- text contains ---

        f.reasonContains
          .map(_.trim)
          .filter(_.nonEmpty)
          .foreach { needle =>
            predicates += cb.like(
              cb.lower(root.get[String]("reason")),
              s"%${needle.toLowerCase}%"
            )
          }

        f.messageContains
          .map(_.trim)
          .filter(_.nonEmpty)
          .foreach { needle =>
            predicates += cb.like(
              cb.lower(root.get[String]("message")),
              s"%${needle.toLowerCase}%"
            )
          }

        cb.and(predicates.toArray: _*)
      }

    findAll(spec, pageRequest)
  }
}