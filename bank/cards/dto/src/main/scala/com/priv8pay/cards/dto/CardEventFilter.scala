package com.priv8pay.cards.dto

import com.priv8pay.cards.dto.CardActivity.OriginType
import com.priv8pay.commons.dto.TagComponent

/** CardEvents-specific filter (Criteria-friendly). */
final case class CardEventFilter(
  uids: Seq[String] = Seq.empty,

  cardUids: Seq[String] = Seq.empty,
  excludeCardUids: Seq[String] = Seq.empty,

  actionUids: Seq[String] = Seq.empty,
  excludeActionUids: Seq[String] = Seq.empty,

  activityUids: Seq[String] = Seq.empty,
  excludeActivityUids: Seq[String] = Seq.empty,

  actionRequestUids: Seq[String] = Seq.empty,
  excludeActionRequestUids: Seq[String] = Seq.empty,

  categories: Seq[CardEventCategory] = Seq.empty,
  excludeCategories: Seq[CardEventCategory] = Seq.empty,

  eventTypes: Seq[CardEventType] = Seq.empty,
  excludeEventTypes: Seq[CardEventType] = Seq.empty,

  /**
   * Return only legacy Web App timeline events.
   *
   * When true, CARDS applies an exact category + eventType allow-list before
   * pagination. This keeps card_event useful for audit while preserving the
   * legacy timeline contract for BFF/Web queries.
   */
  webTimelineOnly: Boolean = false,

  causes: Seq[CardEventCause] = Seq.empty,
  excludeCauses: Seq[CardEventCause] = Seq.empty,

  originTypes: Seq[OriginType] = Seq.empty,
  excludeOriginTypes: Seq[OriginType] = Seq.empty,

  originUserUids: Seq[String] = Seq.empty,
  excludeOriginUserUids: Seq[String] = Seq.empty,

  originAuthorities: Seq[Authority] = Seq.empty,
  excludeOriginAuthorities: Seq[Authority] = Seq.empty,

  /** Optional filter via the linked action's actionType. */
  actionTypes: Seq[CardAction.ActionType] = Seq.empty,
  excludeActionTypes: Seq[CardAction.ActionType] = Seq.empty,

  /** ACL scoping via event.card.cardholder.organization */
  orgUids: Seq[String] = Seq.empty,
  excludeOrgUids: Seq[String] = Seq.empty,

  /** ACL scoping via event.card.cardholder */
  userUids: Seq[String] = Seq.empty,
  excludeUserUids: Seq[String] = Seq.empty,

  dateFrom: Option[Long] = None,
  dateTo: Option[Long] = None,

  /** Include/exclude by event.card.tags (ANY semantics). */
  tags: Seq[TagComponent] = Seq.empty,
  excludeTags: Seq[TagComponent] = Seq.empty,

  reasonContains: Option[String] = None,
  messageContains: Option[String] = None
) {

  private def fmtTag(t: TagComponent): Option[String] = {
    val k = Option(t).flatMap(x => Option(x.kind)).map(_.trim).filter(_.nonEmpty)
    val v = Option(t).flatMap(x => Option(x.value)).map(_.trim).filter(_.nonEmpty)
    (k, v) match {
      case (Some(kk), Some(vv)) => Some(s"$kk=$vv")
      case _                    => None
    }
  }

  override def toString: String = {
    val parts = scala.collection.mutable.ArrayBuffer[String]()

    if (uids.nonEmpty) parts += s"uids=${uids.mkString("[", ",", "]")}"

    if (cardUids.nonEmpty) parts += s"cardUids=${cardUids.mkString("[", ",", "]")}"
    if (excludeCardUids.nonEmpty) parts += s"excludeCardUids=${excludeCardUids.mkString("[", ",", "]")}"

    if (actionUids.nonEmpty) parts += s"actionUids=${actionUids.mkString("[", ",", "]")}"
    if (excludeActionUids.nonEmpty) parts += s"excludeActionUids=${excludeActionUids.mkString("[", ",", "]")}"

    if (activityUids.nonEmpty) parts += s"activityUids=${activityUids.mkString("[", ",", "]")}"
    if (excludeActivityUids.nonEmpty) parts += s"excludeActivityUids=${excludeActivityUids.mkString("[", ",", "]")}"

    if (actionRequestUids.nonEmpty) parts += s"actionRequestUids=${actionRequestUids.mkString("[", ",", "]")}"
    if (excludeActionRequestUids.nonEmpty) parts += s"excludeActionRequestUids=${excludeActionRequestUids.mkString("[", ",", "]")}"

    if (categories.nonEmpty) parts += s"categories=${categories.mkString("[", ",", "]")}"
    if (excludeCategories.nonEmpty) parts += s"excludeCategories=${excludeCategories.mkString("[", ",", "]")}"

    if (eventTypes.nonEmpty) parts += s"eventTypes=${eventTypes.mkString("[", ",", "]")}"
    if (excludeEventTypes.nonEmpty) parts += s"excludeEventTypes=${excludeEventTypes.mkString("[", ",", "]")}"
    if (webTimelineOnly) parts += "webTimelineOnly=true"

    if (causes.nonEmpty) parts += s"causes=${causes.mkString("[", ",", "]")}"
    if (excludeCauses.nonEmpty) parts += s"excludeCauses=${excludeCauses.mkString("[", ",", "]")}"

    if (originTypes.nonEmpty) parts += s"originTypes=${originTypes.mkString("[", ",", "]")}"
    if (excludeOriginTypes.nonEmpty) parts += s"excludeOriginTypes=${excludeOriginTypes.mkString("[", ",", "]")}"

    if (originUserUids.nonEmpty) parts += s"originUserUids=${originUserUids.mkString("[", ",", "]")}"
    if (excludeOriginUserUids.nonEmpty) parts += s"excludeOriginUserUids=${excludeOriginUserUids.mkString("[", ",", "]")}"

    if (originAuthorities.nonEmpty) parts += s"originAuthorities=${originAuthorities.mkString("[", ",", "]")}"
    if (excludeOriginAuthorities.nonEmpty) parts += s"excludeOriginAuthorities=${excludeOriginAuthorities.mkString("[", ",", "]")}"

    if (actionTypes.nonEmpty) parts += s"actionTypes=${actionTypes.mkString("[", ",", "]")}"
    if (excludeActionTypes.nonEmpty) parts += s"excludeActionTypes=${excludeActionTypes.mkString("[", ",", "]")}"

    if (orgUids.nonEmpty) parts += s"orgUids=${orgUids.mkString("[", ",", "]")}"
    if (excludeOrgUids.nonEmpty) parts += s"excludeOrgUids=${excludeOrgUids.mkString("[", ",", "]")}"

    if (userUids.nonEmpty) parts += s"userUids=${userUids.mkString("[", ",", "]")}"
    if (excludeUserUids.nonEmpty) parts += s"excludeUserUids=${excludeUserUids.mkString("[", ",", "]")}"

    dateFrom.foreach(v => parts += s"dateFrom=$v")
    dateTo.foreach(v => parts += s"dateTo=$v")

    val tagsStr = tags.flatMap(fmtTag)
    if (tagsStr.nonEmpty) parts += s"tags=${tagsStr.mkString("[", ",", "]")}"

    val exclTagsStr = excludeTags.flatMap(fmtTag)
    if (exclTagsStr.nonEmpty) parts += s"excludeTags=${exclTagsStr.mkString("[", ",", "]")}"

    reasonContains.foreach(v => parts += s"reasonContains=$v")
    messageContains.foreach(v => parts += s"messageContains=$v")

    val body =
      if (parts.isEmpty) "<empty>"
      else parts.mkString(", ")

    s"CardEventFilter($body)"
  }
}