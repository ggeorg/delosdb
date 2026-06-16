package com.priv8pay.travel.api.srv.driver

import com.priv8pay.cards.dto
import com.priv8pay.cards.dto.Card.CardState
import com.priv8pay.cards.dto.Card.CardState.ACTIVE
import com.priv8pay.cards.dto.{Card, _}
import com.priv8pay.commons.dto.search.{SearchRequest, SortKey}
import com.priv8pay.commons.dto.{Page, SortDirection, TagComponent}
import com.priv8pay.commons.utils.string.StringUtils.nonBlankOpt
import com.priv8pay.commons.utils.{SecurityUtils, UidUtils}
import com.priv8pay.profile.domain._
import com.priv8pay.profile.domain.cardmgmt.{Activity, CardActivityUpdate}
import com.priv8pay.travel.api.client.model.profile.DebitCardPage
import com.priv8pay.travel.api.srv.driver.Conversions._
import com.priv8pay.travel.api.srv.remote.{CardsApiAdapter, ProfileServiceAdapter}
import org.apache.commons.lang3.StringUtils
import org.slf4j.{Logger, LoggerFactory}
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

import java.util
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[driver] object CardsDriverImpl {
  private val LOG: Logger = LoggerFactory.getLogger(classOf[CardsDriverImpl])

  private val PRODUCT_LABEL = "product-label"
  private val PRODUCT_LABEL_VALUE = "expense"

  private val tagSeq = Seq(TagComponent(PRODUCT_LABEL, PRODUCT_LABEL_VALUE))

  /**
   * Web App card timeline selector.
   *
   * CARDS stores both user-facing timeline rows and internal audit/workflow
   * rows in card_event. The exact category/event-type allow-list is enforced
   * inside CARDS before pagination when this flag is set on CardEventFilter.
   */
  private val WebTimelineOnly = true
}

//noinspection SpringJavaInjectionPointsAutowiringInspection,SpringCacheNamesInspection
@Component
class CardsDriverImpl(
  cardsApiAdapter: CardsApiAdapter,
  employeeServiceAdapter: ProfileServiceAdapter,
  cardStateAwaiter: CardStateAwaiter,
)(implicit ctx: ConversionsCtx) extends CardsDriver {

  import CardsDriverImpl._

  private def search(searchReq: SearchRequest[CardFilter]): Page[Card] =
    cardsApiAdapter.searchCards(searchReq)

  private def searchActionsWithActivities(searchReq: SearchRequest[CardActionFilter]): Page[CardActionWithActivities] =
    cardsApiAdapter.searchActionsWithActivities(searchReq)

  private def searchApprovalActionsWithActivities(searchReq: SearchRequest[CardApprovalFilter]): Page[CardActionWithActivities] =
    cardsApiAdapter.searchApprovalActionsWithActivities(searchReq)

  private def searchEvents(searchReq: SearchRequest[CardEventFilter]): Page[CardEvent] =
    cardsApiAdapter.searchEvents(searchReq)


  private def searchCardholderCards(searchReq: SearchRequest[CardholderFilter]): Page[CardholderCards] =
    cardsApiAdapter.searchCardholderCards(searchReq)

  override def getCard(uid: String): cardmgmt.Card = {
    LOG.trace(s">>> getCard: uid=$uid")

    require(nonBlankOpt(uid).isDefined, "uid must be provided")

    val cardWithWorkflow = cardsApiAdapter.cardWithWorkflowByUid(uid)
    val card = Option(cardWithWorkflow).map(_.getCard).orNull

    if (!hasExpenseProductLabel(card)) {
      null
    } else {
      toCardMgmtCard(cardWithWorkflow)
    }
  }

  private def hasExpenseProductLabel(card: Card): Boolean = {
    Option(card)
      .flatMap(c => Option(c.getTags))
      .exists(_.exists(tag =>
        tag != null &&
          PRODUCT_LABEL == tag.kind &&
          PRODUCT_LABEL_VALUE == tag.value
      ))
  }

  /** Get Cards for current user */
  override def listCards(states: util.List[String], fields: util.List[String], page: Int, size: Int): Page[cardmgmt.Card] = {
    LOG.trace(s">>> listCards states=$states, fields=$fields, page=$page, size=$size")

    require(page >= 0, s"page must be >= 0 (was $page)")

    val cardStates = if (Option(states).isDefined && !states.isEmpty) {
      states.asScala.map { state =>
        state: CardState
      }.toSeq
    } else Seq[CardState]()

    // Call CARDS with status=ACTIVE
    val searchReq = SearchRequest[CardFilter](
      filter = CardFilter(
        states = cardStates,
        authority = Some(Authority.employee),
        tags = tagSeq
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = Nil, // no sorting
      expand = Map.empty,
      excludeFields = Map.empty
    )
    val cardsPage = search(searchReq)

    LOG.info(s"========================= $cardsPage")
    cardsPage.getContent.asScala.foreach { card =>
      LOG.debug(s"card: $card, tags: ${card.getTags.mkString(", ")}")
    }

    cardsPage // implicit Page[Card] → Page[CardMgmtCard] via Conversions
  }

  /** List active cards for a given employee (employeeUid → userUid via profile, then query CARDS) */
  override def listEmployeeActiveCards(empUid: String, page: Int, size: Int): Page[cardmgmt.Card] = {
    LOG.trace(s">>> listEmployeeActiveCards: empUid=$empUid, page=$page, size=$size")

    require(nonBlankOpt(empUid).isDefined, "employee uid must be provided")
    require(page >= 0, s"page must be >= 0 (was $page)")

    // Resolve employee → user (PROFILE adapter; cached identity mapping)
    val userUid = employeeServiceAdapter.resolveUserUidForEmployee(empUid)

    // Call CARDS with status=ACTIVE
    val searchReq = SearchRequest[CardFilter](
      filter = CardFilter(
        userUids = Some(userUid).toSeq,
        states = Array(ACTIVE),
        tags = tagSeq
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = Nil, // no sorting
      expand = Map.empty,
      excludeFields = Map.empty
    )
    val cardsPage = search(searchReq)

    LOG.info(s"========================= $cardsPage")

    cardsPage // implicit Page[Card] → Page[CardMgmtCard] via Conversions
  }

  override def listEmployeeCards(empUid: String, page: Int, size: Int): DebitCardPage = {
    LOG.trace(s">>> listEmployeeCards: empUid=$empUid, page=$page, size=$size")

    require(nonBlankOpt(empUid).isDefined, "employee uid must be provided")
    require(page >= 0, s"page must be >= 0 (was $page)")

    // Resolve employee → user (PROFILE adapter; cached identity mapping)
    val userUid = employeeServiceAdapter.resolveUserUidForEmployee(empUid)

    // Call CARDS
    val searchReq = SearchRequest[CardFilter](
      filter = CardFilter(
        userUids = Some(userUid).toSeq,
        tags = tagSeq
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = Nil, // no sorting
      expand = Map.empty,
      excludeFields = Map.empty
    )
    val cardsPage = search(searchReq)

    LOG.info(s"========================= $cardsPage")

    cardsPage // implicit Page[Card] → DebitCardPage via Conversions
  }

  /** Get Cards for divison employees where current user is BizAdmin */
  override def listCardsAsAdmin(emplike: String, last4: String, page: Int, size: Int): Page[cardmgmt.UserCard] = {
    LOG.trace(s">>> listCardsAsAdmin: emplike=$emplike, last4=$last4, page=$page, size=$size")

    require(page >= 0, s"page must be >= 0 (was $page)")

    val actorUserUid = currentActorUserUid()

    val searchReq = buildCardholderCardsSearchRequest(
      empLikeOpt = nonBlankOpt(emplike),
      excludeUids = Seq(actorUserUid),
      last4Opt = nonBlankOpt(last4),
      page = page,
      size = size
    )

    LOG.trace("cardholder search filter={}", searchReq.filter.toString)

    val cardholderCardsPage: Page[CardholderCards] = searchCardholderCards(searchReq)

    LOG.debug(
      "listCardsAsAdmin result: total={}, page={}, size={}",
      Long.box(cardholderCardsPage.getTotalElements),
      Int.box(cardholderCardsPage.getNumber),
      Int.box(cardholderCardsPage.getSize)
    )

    toCardMgmtUserCardsPageFromCardholderCards(cardholderCardsPage)
  }

  /** Get Cards for divison employees where current user is BizAdmin */
  override def listCardsAsBankAdmin(orgUid: String, emplike: String, last4: String, page: Int, size: Int): Page[cardmgmt.UserCard] = {
    LOG.trace(
      ">>> listCardsAsBankAdmin: orgUid={}, emplike={}, last4={}, page={}, size={}", orgUid, emplike, last4, Int.box(page), Int.box(size)
    )

    require(nonBlankOpt(orgUid).isDefined, "organization uid must be provided")
    require(page >= 0, s"page must be >= 0 (was $page)")

    val actorUserUid = currentActorUserUid()

    val searchReq = buildCardholderCardsSearchRequest(
      orgUids = Seq(orgUid),
      empLikeOpt = nonBlankOpt(emplike),
      excludeUids = Seq(actorUserUid),
      last4Opt = nonBlankOpt(last4),
      page = page,
      size = size
    )

    LOG.trace("cardholder search filter={}", searchReq.filter.toString)

    val cardholderCardsPage: Page[CardholderCards] = searchCardholderCards(searchReq)

    LOG.debug(
      "listCardsAsBankAdmin result: total={}, page={}, size={}",
      Long.box(cardholderCardsPage.getTotalElements),
      Int.box(cardholderCardsPage.getNumber),
      Int.box(cardholderCardsPage.getSize)
    )

    toCardMgmtUserCardsPageFromCardholderCards(cardholderCardsPage)
  }

  /** Get Cards of subordinates of current user */
  override def listTeamCards(emplike: String, last4: String, page: Int, size: Int): Page[cardmgmt.UserCard] = {
    LOG.trace(
      ">>> listTeamCards: emplike={}, last4={}, page={}, size={}", emplike, last4, Int.box(page), Int.box(size)
    )

    require(page >= 0, s"page must be >= 0 (was $page)")

    val actorUserUid = currentActorUserUid()

    val searchReq = buildCardholderCardsSearchRequest(
      empLikeOpt = nonBlankOpt(emplike),
      excludeUids = Seq(actorUserUid),
      authorityOpt = Some(Authority.supervisor),
      last4Opt = nonBlankOpt(last4),
      page = page,
      size = size
    )

    LOG.trace("cardholder search filter={}", searchReq.filter.toString)

    val cardholderCardsPage: Page[CardholderCards] = searchCardholderCards(searchReq)

    LOG.debug(
      "<<< listTeamCards result: total={}, page={}, size={}",
      Long.box(cardholderCardsPage.getTotalElements),
      Int.box(cardholderCardsPage.getNumber),
      Int.box(cardholderCardsPage.getSize)
    )

    toCardMgmtUserCardsPageFromCardholderCards(cardholderCardsPage)
  }

  /**
   * Build the common Cardholder search request used by user-card listing endpoints.
   *
   * Common behavior for all callers:
   *   - employee/cardholder search term and last4 are mutually exclusive
   *   - org scoping is applied only when orgUids are provided
   *   - expense-card product filtering is always enforced
   *   - when last4 is present, both the holder set and selected cards are filtered by last4
   *   - when last4 is absent, selected cards are filtered only by the common expense tag
   *   - CardholderCards always returns the cardholder/cards relationship explicitly
   *   - CardholderCards also returns workflow relation maps needed by the webapp card-management table
   */
  private def buildCardholderCardsSearchRequest(
    orgUids: Seq[String] = Seq.empty,
    empLikeOpt: Option[String],
    excludeUids: Seq[String],
    authorityOpt: Option[Authority] = None,
    last4Opt: Option[String],
    page: Int,
    size: Int
  ): SearchRequest[CardholderFilter] = {
    require(
      !(empLikeOpt.nonEmpty && last4Opt.nonEmpty),
      s"search term and last4 are mutually exclusive, but both were provided: term=${empLikeOpt.orNull}, last4=${last4Opt.orNull}"
    )

    SearchRequest[CardholderFilter](
      filter = CardholderFilter(
        orgUids = orgUids,
        term = empLikeOpt,
        excludeUids = excludeUids,
        authority = authorityOpt,

        // last4 present -> DB-level restriction (return only matching holders)
        hasCardLast4 = last4Opt,
        hasCardTags = if (last4Opt.nonEmpty) tagSeq else Seq.empty,

        // also filter selected cards when last4 is present
        selectedCardsLast4 = last4Opt,
        selectedCardsTags = if (last4Opt.isEmpty) tagSeq else Seq.empty
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = Nil, // no sorting
      expand = Map.empty,
      excludeFields = Map.empty
    )
  }

  /** Get Cards for division employees where current user is BizAdmin */
  override def listCardsAsBizAdmin(orgUid: String, cardholder: String, last4: String, page: Int, size: Int): Page[cardmgmt.UserCard] = {
    LOG.trace(
      ">>> listCardsAsBizAdmin: orgUid={}, cardholder={}, last4={}, page={}, size={}",
      orgUid, cardholder, last4, Int.box(page), Int.box(size)
    )

    require(nonBlankOpt(orgUid).isDefined, "organization uid must be provided")
    require(page >= 0, s"page must be >= 0 (was $page)")

    val actorUserUid = currentActorUserUid()

    val searchReq = buildCardholderCardsSearchRequest(
      orgUids = Seq(orgUid),
      empLikeOpt = nonBlankOpt(cardholder),
      excludeUids = Seq(actorUserUid),
      last4Opt = nonBlankOpt(last4),
      page = page,
      size = size
    )

    LOG.trace("cardholder search filter={}", searchReq.filter.toString)

    val cardholderCardsPage: Page[CardholderCards] = searchCardholderCards(searchReq)

    LOG.debug(
      "listCardsAsBizAdmin result: total={}, page={}, size={}",
      Long.box(cardholderCardsPage.getTotalElements),
      Int.box(cardholderCardsPage.getNumber),
      Int.box(cardholderCardsPage.getSize)
    )

    toCardMgmtUserCardsPageFromCardholderCards(cardholderCardsPage)
  }

  def listResellerCards(states: util.List[String], empLike: String, clientUid: String, empUid: String, page: Integer, size: Integer): DebitCardPage = {
    LOG.trace(s">>> listResellerCards: states=$states, empLike=$empLike, clientUid=$clientUid, empUid=$empUid, page=$page, size=$size")

    require(page >= 0, s"page must be >= 0 (was $page)")

    val cardStates = if (Option(states).isDefined && !states.isEmpty) {
      states.asScala.map { state =>
        state: CardState
      }.toSeq
    } else Seq[CardState]()

    // Resolve employee → user (PROFILE adapter; cached identity mapping)
    val userUid =
      Option(empUid) match {
        case Some(_) => employeeServiceAdapter.resolveUserUidForEmployee(empUid)
        case None => null
      }

    // Call CARDS
    val searchReq = SearchRequest[CardFilter](
      filter = CardFilter(
        states = cardStates,
        orgUids = Option(clientUid).toSeq,
        userUids = Option(userUid).toSeq,
        cardholder = Option(empLike),
        tags = tagSeq
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = Nil, // no sorting
      // DebitCardPage conversion does not consume card workflow data.
      // Keep this reseller listing on the plain card page path.
      expand = Map.empty,
      excludeFields = Map.empty
    )
    val cardsPage = search(searchReq)

    LOG.info(s"========================= $cardsPage")

    cardsPage // implicit Page[Card] → DebitCardPage via Conversions
  }

  override def createCard(empUid: String, cardReq: cardmgmt.NewCardRequest): cardmgmt.Card = {
    LOG.trace(s">>> createCard: empUid=$empUid, cardReq=$cardReq")

    // Resolve employee → user (PROFILE adapter; cached identity mapping)
    Option(empUid)
      .map(euid => employeeServiceAdapter.resolveUserUidForEmployee(euid))
      .foreach(userUid => cardReq.setTargetUserUuid(userUid))

    val cardAction: CardAction =
      cardsApiAdapter.addCardAction(cardReq)

    getCard(cardAction.getCardUid)
  }

  override def updateCard(uid: String, updateRequest: cardmgmt.CardUpdate): cardmgmt.Card = {
    LOG.trace(s">>> update: uid=$uid, updateRequest=$updateRequest")

    val cardData: Card = updateRequest
    cardData.setUid(uid)

    cardsApiAdapter.updateCard(cardData)
  }

  override def createAction(cardUid: String, updateRequest: cardmgmt.CardUpdate): cardmgmt.Card = {
    LOG.trace(s">>> createAction: cardUid=$cardUid, updateRequest=$updateRequest")

    require(nonBlankOpt(cardUid).isDefined, "card uid must be provided")

    val action = Option(updateRequest.getCardAction).map(_.getAction).getOrElse {
      throw new IllegalArgumentException("card action must be provided")
    }

    val cardAction: dto.CardAction =
      cardsApiAdapter.addCardAction((cardUid, updateRequest))

    action match {
      case cardmgmt.CardAction.Action.activate =>
        cardStateAwaiter.awaitActivated(cardAction.getCardUid)
      case _ =>
        // mirror old behavior for non-activation actions
        cardsApiAdapter.cardByUid(cardAction.getCardUid)
    }
  }

  override def listEvents(cardUid: String, page: Int, size: Int): Page[cardmgmt.CardEvent] = {
    LOG.trace(s">>> listEvents: cardUid=$cardUid, page=$page, size=$size")

    val maybeCardUid = nonBlankOpt(cardUid)

    require(maybeCardUid.isDefined, "card uid must be provided")
    require(page >= 0, s"page must be >= 0 (was $page)")

    val effectiveSize = if (size <= 0) Int.MaxValue else size

    val searchReq = SearchRequest[CardEventFilter](
      filter = CardEventFilter(
        cardUids = maybeCardUid.toSeq,
        webTimelineOnly = WebTimelineOnly,
        tags = tagSeq
      ),
      page = Some(page),
      size = Some(effectiveSize),
      sort = List(
        SortKey("creationTime", SortDirection.DESC)
      )
    )

    searchEvents(searchReq)
  }

  override def listActions(cardUid: String, states: util.List[String], page: Int, size: Int): Page[cardmgmt.Activity] = {
    LOG.trace(s">>> listActions: cardUid=$cardUid, states=$states, page=$page, size=$size")

    val maybeCardUid = nonBlankOpt(cardUid)

    require(page >= 0, s"page must be >= 0 (was $page)")
    // Note: cardUid maybe null

    val activityStates = if (Option(states).isDefined && !states.isEmpty) {
      states.asScala.map { state =>
        state: CardActivity.Progress
      }.toSeq
    } else Seq[CardActivity.Progress]()

    val searchReq = SearchRequest[CardActionFilter](
      filter = CardActionFilter(
        cardUids = maybeCardUid.toSeq,
        lastActivityProgress = activityStates,
        tags = tagSeq,
        authority = None
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = List(
        SortKey("creationTime", SortDirection.DESC) // order by creationTime DESC
      )
    )

    val actionsPage: Page[CardActionWithActivities] = searchActionsWithActivities(searchReq)

    LOG.info(s"========================= $actionsPage")

    actionsPage
  }

  private def requestedAuthorityFor(authorities: Seq[Authority]): Option[Authority] =
    authorities.sortBy(_.getRank).lastOption

  private def supervisorApprovalAuthorities(): Seq[Authority] =
    Seq(Authority.supervisor)

  private def adminApprovalAuthoritiesForCurrentUser(): Seq[Authority] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Authority]

    if (SecurityUtils.loggedInHasAnyRole(true, "ROLE_CORPADMIN")) {
      out += Authority.orgAdmin
    }
    if (SecurityUtils.loggedInHasAnyRole(true, "ROLE_ADMIN")) {
      out += Authority.divisionAdmin
    }

    out.distinct.toSeq
  }

  private def currentActorUserUid(): String = {
    val email = SecurityUtils.loggedInUserNaturalKey()
    //val actorEmpUid = employeeServiceAdapter.resolveEmployeeUidByEmail(email)
    val actorUserUid = employeeServiceAdapter.resolveUserUidByEmail(email)

    LOG.debug("actor email={}, actorUserUid={}", email, actorUserUid)
    require(nonBlankOpt(actorUserUid).isDefined, s"actor user uid not found for email=$email")

    actorUserUid
  }

  private def listApprovalActionsByAuthorities(
    progresses: util.List[String],
    cardUid: String,
    page: Int,
    size: Int,
    authorities: Seq[Authority]
  ): Page[cardmgmt.BaseActivity] = {
    LOG.trace(
      ">>> listApprovalActionsByAuthorities: progresses={}, cardUid={}, page={}, size={}, authorities={}",
      progresses,
      cardUid,
      Int.box(page),
      Int.box(size),
      authorities.mkString("[", ",", "]")
    )

    require(page >= 0, s"page must be >= 0 (was $page)")

    val maybeCardUid = nonBlankOpt(cardUid)
    val actorUserUid = currentActorUserUid()

    val activityProgresses: Seq[CardActivity.Progress] =
      if (Option(progresses).isDefined && !progresses.isEmpty) {
        progresses.asScala.map(progress => progress: CardActivity.Progress).toSeq
      } else {
        Seq.empty
      }

    val includeCreatorBranch = maybeCardUid.isEmpty
    val requestedAuthority = requestedAuthorityFor(authorities)

    val searchReq = SearchRequest[CardApprovalFilter](
      filter = CardApprovalFilter(
        cardUids = maybeCardUid.toSeq,

        // legacy:
        // - specific card => approver only
        // - inbox/list => creator-or-approver
        originUserUids =
          if (includeCreatorBranch) Seq(actorUserUid) else Seq.empty,

        approverAuthorities = authorities,
        authority = requestedAuthority,

        progress = activityProgresses,
        currentStepOnly = Some(true),
        legacyActorMode = Some(includeCreatorBranch),

        tags = tagSeq
      ),
      page = Some(page),
      size = Some(if (size <= 0) Int.MaxValue else size),
      sort = List(
        SortKey("creationTime", SortDirection.DESC)
      )
    )

    LOG.trace("approval search filter={}", searchReq.filter.toString)

    val actionsPage: Page[CardActionWithActivities] = searchApprovalActionsWithActivities(searchReq)

    LOG.debug(
      "approval search result: total={}, page={}, size={}, authorities={}, requestedAuthority={}",
      Long.box(actionsPage.getTotalElements),
      Int.box(actionsPage.getNumber),
      Int.box(actionsPage.getSize),
      authorities.mkString("[", ",", "]"),
      requestedAuthority.orNull
    )

    actionsPage
  }

  override def listApprovalActionsAsSupervisor(
    progresses: util.List[String],
    cardUid: String,
    page: Int,
    size: Int
  ): Page[cardmgmt.BaseActivity] = {
    listApprovalActionsByAuthorities(
      progresses = progresses,
      cardUid = cardUid,
      page = page,
      size = size,
      authorities = supervisorApprovalAuthorities()
    )
  }

  override def listApprovalActionsAsAdmin(
    progresses: util.List[String],
    cardUid: String,
    page: Int,
    size: Int
  ): Page[cardmgmt.BaseActivity] = {
    val authorities = adminApprovalAuthoritiesForCurrentUser()

    if (authorities.isEmpty) {
      val empty = new Page[cardmgmt.BaseActivity]()
      empty.setContent(java.util.Collections.emptyList())
      empty.setNumber(page)
      empty.setSize(if (size <= 0) Int.MaxValue else size)
      empty.setNumberOfElements(0)
      empty.setTotalElements(0L)
      empty.setTotalPages(0)
      empty.setHasContent(false)
      empty.setFirst(page == 0)
      empty.setLast(true)
      empty.setHasPrevious(page > 0)
      empty.setHasNext(false)
      empty
    } else {
      listApprovalActionsByAuthorities(
        progresses = progresses,
        cardUid = cardUid,
        page = page,
        size = size,
        authorities = authorities
      )
    }
  }

  def findActionByUid(actionUid: String): cardmgmt.Activity = {
    LOG.trace(s">>> findActionByUid: actionUid=$actionUid")

    require(nonBlankOpt(actionUid).isDefined, "card action uid must be provided")

    toCardMgmtActivity(cardsApiAdapter.findActionWithActivitiesByUid(actionUid))
  }

  @Cacheable(
    cacheNames = Array("short"),
    key = "#userUid == null ? null : #userUid.trim()", // cache per trimmed user uid
    unless = "#result == null || #result.isEmpty()" // don't cache null/empty results
  )
  override def getCardGroupCounts(userUid: String): List[CardGroupCounts] = {
    LOG.trace(s">>> getCardGroupCounts: userUid=$userUid")

    require(nonBlankOpt(userUid).isDefined, "user uid must be provided")

    // get card activities as supervisor
    val cardActivities: Page[CardActivity] =
      cardsApiAdapter.findActivities(
        cardUid = null,
        states = Array(CardActivity.Progress.PENDING),
        page = 0,
        size = Integer.MAX_VALUE
      )

    LOG.info("========================= cardActivities={}", cardActivities)

    val pendingCount = 0 // Option(cardActivities).map(_.getNumberOfElements.intValue()).getOrElse(0)

    val cardGroupCount: CardGroupCounts = new CardGroupCounts()
    cardGroupCount.setCardGroupCounts(pendingCount, "card", "pending")

    var cardEvents: List[CardGroupCounts] = List[CardGroupCounts]()
    cardEvents = cardEvents :+ cardGroupCount

    val myCardEvents = getOwnCardGroupCounts(userUid)
    cardEvents = cardEvents ++ myCardEvents

    cardEvents
  }

  private def getOwnCardGroupCounts(userUid: String): List[CardGroupCounts] = {
    LOG.trace(s">>> getOwnCardGroupCounts: userUid=$userUid")

    // Build CARDS search request for "my own cards"
    val searchReq = SearchRequest[CardFilter](
      filter = CardFilter(
        userUids = Some(userUid).toSeq,
        authority = Some(Authority.employee),
        tags = tagSeq
      ),
      page = Some(0),
      size = Some(Int.MaxValue)
    )

    val cardsPage: Page[Card] = search(searchReq)

    LOG.info(s"========================= $cardsPage")

    val cards: Seq[Card] =
      Option(cardsPage)
        .map(_.getContent)
        .map(_.asScala.toSeq)
        .getOrElse(Seq.empty)

    // Group by state (status) and count — only existing states, like the SQL GROUP BY
    val grouped: Seq[(Card.CardState, Int)] =
      cards
        .flatMap(c => Option(c.getState)) // ignore null states just in case
        .groupBy(identity) // Map[CardState, Seq[CardState]]
        .view // applies transformations lazily
        .mapValues(_.size) // MapView[CardState, Int]
        .toSeq
        .sortBy(_._1.name()) // stable ordering by enum name

    val result: List[CardGroupCounts] =
      grouped.map { case (state, cnt) =>
        val dto = new CardGroupCounts
        dto.setCardGroupCounts(
          cnt, // count
          "mycard", // ctx (matches SQL: 'mycard' as ctx)
          state.name().toLowerCase() // status
        )
        dto
      }.toList

    LOG.debug(
      "[getOwnCardGroupCounts] userUid={}, result={}",
      userUid,
      result.mkString("[", ", ", "]")
    )

    result
  }

  override def updateCardActionByUid(actionUid: String, updateRequest: CardActivityUpdate): Activity = {
    LOG.trace(s">>> updateCardActionByUid: actionUid=$actionUid, updateRequest=$updateRequest")

    require(nonBlankOpt(actionUid).isDefined, "card action uid must be provided")
    require(updateRequest != null, "updateRequest must be provided")
    require(updateRequest.getActionEnum != null, "updateRequest action must be provided")

    normalizeAttentionToLineForCards(updateRequest)

    cardsApiAdapter.updateCardActionByUid(actionUid, updateRequest)
    toCardMgmtActivity(cardsApiAdapter.findActionWithActivitiesByUid(actionUid))
  }

  /**
   * Card activity updates come from the Profile/API model, where the expedite
   * attention-to field is an employee UID. CARDS stores cardholders/users, so
   * normalize the update payload at the BFF boundary before forwarding it.
   */
  private def normalizeAttentionToLineForCards(updateRequest: CardActivityUpdate): Unit = {
    val expediteOpt =
      Option(updateRequest.getNewCardRequest)
        .flatMap(req => Option(req.fetchBaseCardRequest()))
        .flatMap(req => Option(req.getExpedite))

    expediteOpt.foreach { expedite =>
      val isExpedited =
        java.lang.Boolean.TRUE.equals(expedite.getProcessing) ||
          java.lang.Boolean.TRUE.equals(expedite.getShipping)

      if (!isExpedited) {
        expedite.setAttentionToEmployeeUid(null)
        expedite.setAttentionToEmployeeName(null)
      } else {
        nonBlankOpt(expedite.getAttentionToEmployeeUid).foreach { employeeUid =>
          val cardsUserUid = resolveAttentionToCardsUserUid(employeeUid)
          expedite.setAttentionToEmployeeUid(cardsUserUid)
        }
      }
    }
  }

  private def resolveAttentionToCardsUserUid(employeeUid: String): String = {
    try {
      employeeServiceAdapter.resolveUserUidForEmployee(employeeUid)
    } catch {
      case NonFatal(ex) =>
        LOG.debug(
          "Could not resolve attention-to uid {} as Profile employee uid; forwarding original uid to CARDS",
          employeeUid,
          ex
        )
        employeeUid
    }
  }
}