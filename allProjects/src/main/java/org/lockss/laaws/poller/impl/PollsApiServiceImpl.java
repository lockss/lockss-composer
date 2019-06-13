/*
 * Copyright (c) 2019 Board of Trustees of Leland Stanford Jr. University,
 * all rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * STANFORD UNIVERSITY BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Except as contained in this notice, the name of Stanford University shall not
 * be used in advertising or otherwise to promote the sale, use or other dealings
 * in this Software without prior written authorization from Stanford University.
 */
package org.lockss.laaws.poller.impl;

import java.net.MalformedURLException;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.lockss.app.LockssApp;
import org.lockss.app.LockssDaemon;
import org.lockss.laaws.poller.api.PollsApi;
import org.lockss.laaws.poller.api.PollsApiDelegate;
import org.lockss.laaws.poller.model.*;
import org.lockss.laaws.poller.model.PollDesc.VariantEnum;
import org.lockss.laaws.poller.model.RepairData.ResultEnum;
import org.lockss.laaws.status.model.ApiStatus;
import org.lockss.plugin.ArchivalUnit;
import org.lockss.plugin.CachedUrlSet;
import org.lockss.plugin.PluginManager;
import org.lockss.poller.Poll;
import org.lockss.poller.PollManager;
import org.lockss.poller.PollManager.NotEligibleException;
import org.lockss.poller.PollSpec;
import org.lockss.poller.v3.*;
import org.lockss.poller.v3.ParticipantUserData.VoteCounts;
import org.lockss.poller.v3.PollerStateBean.Repair;
import org.lockss.poller.v3.PollerStateBean.TallyStatus;
import org.lockss.protocol.PeerIdentity;
import org.lockss.protocol.psm.PsmInterp;
import org.lockss.protocol.psm.PsmState;
import org.lockss.spring.status.SpringLockssBaseApiController;
import org.lockss.util.ByteArray;
import org.lockss.util.StringUtil;
import org.lockss.util.UrlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * The Polls api service.
 */
@Service
public class PollsApiServiceImpl
    implements PollsApiDelegate {

  private static Logger logger = LoggerFactory
      .getLogger(PollsApiServiceImpl.class);
  private PollManager pollManager;
  private PluginManager pluginManager;
  private LockssDaemon theDaemon;
  @Autowired
  private HttpServletRequest request;
  private static final String DETAIL_UNAVAILABLE = "Unable to add details link.";


  /* ------------------------------------------------------------------------
        SpringLockssBaseApiController implementation.
       ------------------------------------------------------------------------
    */

  public ApiStatus getApiStatus() {
    return new ApiStatus("swagger/swagger.yaml")
        .setReady(LockssApp.getLockssApp().isAppRunning());
  }

  /* ------------------------------------------------------------------------
      PollsApiDelegate implementation.
     ------------------------------------------------------------------------
  */

  /*  -------------------Poll Service methods. --------------------------- */

  /**
   * Call a new poll
   *
   * @param body a description of the poll to call
   * @return the identifier for this poll.
   * @see PollsApi#callPoll
   */
  @Override
  public ResponseEntity<String> callPoll(PollDesc body) {
    ArchivalUnit au = null;
    final String auId = body.getAuId();
    final CachedUriSetSpec cuSetSpec = body.getCuSetSpec();
    if (logger.isDebugEnabled()) {
      logger.debug("request to start a poll for au: " + auId);
    }
    try {
      if (!StringUtil.isNullString(auId)) {
        au = getPluginManager().getAuFromId(auId);
      }
    }
    catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.error("No valid au: " + auId);
      }
    }
    if (au != null) {
      PollSpec ps = pollSpecFromDesc(au, cuSetSpec);
      PollManager pm = getPollManager();
      try {
        pm.requestPoll(ps);
      }
      catch (NotEligibleException e) {
        logger.error(e.getMessage());
        return new ResponseEntity<>(e.getMessage(), HttpStatus.FORBIDDEN);
      }
      return new ResponseEntity<>(auId, HttpStatus.ACCEPTED);
    }
    return new ResponseEntity<>("No valid au: " + auId, HttpStatus.NOT_FOUND);

  }


  /**
   * Cancel Poll a previously called poll
   *
   * @param psId the poll service id of the called poll
   * @return Void.
   * @see PollsApi#cancelPoll
   */
  @Override
  public ResponseEntity<Void> cancelPoll(String psId) {
    if (logger.isDebugEnabled()) {
      logger.debug("request to cancel poll for " + psId);
    }
    PollManager pm = getPollManager();
    ArchivalUnit au;
    try {
      au = getPluginManager().getAuFromId(psId);
      Poll poll = pm.stopPoll(au);
      if (poll != null) {
        return new ResponseEntity<>(HttpStatus.OK);
      }
    }
    catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.debug("unable to locate poll with id " + psId);
      }
    }
    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
  }

  /**
   * Return the current status of a poll.
   *
   * @param psId The poll service id of the called poll
   * @return A summary of the current Polls status.
   * @see PollsApi#getPollStatus
   */
  @Override
  public ResponseEntity<PollerSummary> getPollStatus(String psId) {
    if (logger.isDebugEnabled()) {
      logger.debug("request poll info for " + psId);
    }
    ArchivalUnit au;
    try {
      au = getPluginManager().getAuFromId(psId);
      if (au != null) {
        PollManager pm = getPollManager();
        Poll poll = pm.getPoll(au.getAuId());
        if (poll != null) {
          PollerSummary summary = summarizePollerPoll(poll);
          return new ResponseEntity<>(summary, HttpStatus.OK);
        }
      }
    }
    catch (Exception e) {
      if (logger.isDebugEnabled()) {
        logger.debug("unable to locate poll with id " + psId);
      }
    }
    return new ResponseEntity<>(HttpStatus.NOT_FOUND);
  }


  /*  -------------------Poll Detail methods. --------------------------- */

  /**
   * Get the detailed description of a Poller poll.
   *
   * @param pollKey the PollKey assigned by the Poll Manager
   * @return A PollerPoll detail.
   * @see PollsApi#getPollerPollDetails
   */
  @Override
  public ResponseEntity<PollerDetail> getPollerPollDetails(String pollKey) {
    if (logger.isDebugEnabled()) {
      logger.debug("request poller details for poll with " + pollKey);
    }
    PollManager pm = getPollManager();
    Poll poll = pm.getPoll(pollKey);
    if (poll == null) {
      if (logger.isDebugEnabled()) {
        logger.debug("Details for poll " + pollKey + " not found.");
      }
      return new ResponseEntity<>(new PollerDetail(), HttpStatus.NOT_FOUND);
    }
    PollerDetail detail = detailPoll(poll);
    return new ResponseEntity<>(detail, HttpStatus.OK);
  }

  /**
   * Get the detailed description of a Poller poll.
   *
   * @param pollKey the PollKey assigned by the Poll Manager
   * @return A VoterPoll detail.
   * @see PollsApi#getVoterPollDetails
   */
  @Override
  public ResponseEntity<VoterDetail> getVoterPollDetails(String pollKey) {
    if (logger.isDebugEnabled()) {
      logger.debug("request voter details for poll with " + pollKey);
    }
    PollManager pm = getPollManager();
    Poll poll = pm.getPoll(pollKey);
    if (poll == null) {
      if (logger.isDebugEnabled()) {
        logger.debug("Details for poll " + pollKey + " not found.");
      }
      return new ResponseEntity<>(new VoterDetail(), HttpStatus.NOT_FOUND);
    }
    VoterDetail detail = detailVoterPoll(poll);
    return new ResponseEntity<>(detail, HttpStatus.OK);
  }

  /**
   * Get a Participant peers's urls for a Poller
   *
   * @param pollKey the PollKey assigned by the Poll Manager
   * @param peerId the id of the peer
   * @param urls the type of urls to return
   * @param page the page number of the paged results
   * @param size the size of the page.
   * @return A UrlPager of paged urls.
   * @see PollsApi#getPollPeerVoteUrls
   */
  @Override
  public ResponseEntity<UrlPager> getPollPeerVoteUrls(String pollKey,
      String peerId, String urls,
      Integer page, Integer size) {
    PollManager pm = getPollManager();
    Poll poll = pm.getPoll(pollKey);
    String baseLink = request.getRequestURI();
    if (poll instanceof V3Poller) {
      final List<ParticipantUserData> participants = ((V3Poller) poll)
          .getParticipants();
      ParticipantUserData userData = userDataForPeer(peerId, participants);
      if (userData != null) {
        VoteCounts voteCounts = userData.getVoteCounts();
        if (voteCounts.hasPeerUrlLists() && userData.hasVoted()) {
          Collection<String> counts;
          switch (urls) {
            case "agreed":
              counts = voteCounts.getAgreedUrls();
              break;
            case "disagreed":
              counts = voteCounts.getDisagreedUrls();
              break;
            case "pollerOnly":
              counts = voteCounts.getPollerOnlyUrls();
              break;
            case "voterOnly":
              counts = voteCounts.getVoterOnlyUrls();
              break;
            default:
              counts = Collections.emptyList();
          }
          if (counts != null) {
            Page<String> strPage = new Page<>(counts, page, size, baseLink);
            UrlPager pager = getUrlPager(strPage);
            return new ResponseEntity<>(pager, strPage.getPageHeaders(),
                HttpStatus.OK);
          }
        }
      }
    }
    return new ResponseEntity<>(new UrlPager(), HttpStatus.NOT_FOUND);
  }

  /**
   * Return a UrlPager.
   *
   * @param urlPage the Page Description
   * @return a UrlPager from a Page description
   */
  private UrlPager getUrlPager(Page<String> urlPage) {
    PageDesc desc = getPageDesc(urlPage);
    UrlPager pager = new UrlPager();
    pager.setPageDesc(desc);
    pager.setUrls(urlPage.getPageContent());
    return pager;
  }


  /**
   * Return details of form the RepairQueue of a called poll.
   *
   * @param pollKey the PollKey assigned by the Poll Manager
   * @param repair the kind of repair data to return.
   * @param page the page number of the paged results
   * @param size the size of the page.
   * @return A RepairPager of the current page of urls.
   * @see PollsApi#getRepairQueueData
   */
  public ResponseEntity<RepairPager> getRepairQueueData(String pollKey,
      String repair, Integer page,
      Integer size) {
    PollManager pm = getPollManager();
    Poll poll = pm.getPoll(pollKey);
    String baseLink = request.getRequestURI();
    if (poll instanceof V3Poller) {
      PollerStateBean.RepairQueue repairQueue = ((V3Poller) poll)
          .getPollerStateBean()
          .getRepairQueue();
      List<Repair> repairList;
      switch (repair) {
        case "active":
          repairList = repairQueue.getActiveRepairs();
          break;
        case "pending":
          repairList = repairQueue.getPendingRepairs();
          break;
        case "completed":
          repairList = repairQueue.getCompletedRepairs();
          break;
        default:
          repairList = new ArrayList<>();
      }
      if (repairList != null) {
        Page<Repair> rpage = new Page<>(repairList, page, size, baseLink);
        // The page description.
        PageDesc desc = getPageDesc(rpage);
        RepairPager pager = new RepairPager();
        pager.setPageDesc(desc);
        // The page content.
        if (rpage.hasContent()) {
          List<Repair> rlist = rpage.getPageContent();
          for (Repair rep : rlist) {
            RepairData rdata = new RepairData();
            rdata.setRepairUrl(rep.getUrl());
            rdata.setRepairFrom(rep.getRepairFrom().getIdString());
            if ("completed".equals(repair)) {
              rdata.setResult(
                  ResultEnum.fromValue(rep.getTallyResult().toString()));
            }
            pager.addRepairsItem(rdata);
          }
        }
        return new ResponseEntity<>(pager, rpage.getPageHeaders(),
            HttpStatus.OK);
      }
    }
    return new ResponseEntity<>(new RepairPager(), HttpStatus.NOT_FOUND);
  }

  /**
   * Return the Tallied Urls.
   *
   * @param pollKey the PollKey assigned by the Poll Manager
   * @param tally the kind of tally data to return.
   * @param page the page number of the paged results
   * @param size the size of the page.
   * @return A UrlPager of paged urls.
   * @see PollsApi#getTallyUrls
   */
  public ResponseEntity<UrlPager> getTallyUrls(String pollKey, String tally,
      Integer page,
      Integer size) {
    PollManager pm = getPollManager();
    Poll poll = pm.getPoll(pollKey);
    String baseLink = request.getRequestURI();
    if (poll instanceof V3Poller) {
      final PollerStateBean.TallyStatus tallyStatus = ((V3Poller) poll)
          .getPollerStateBean()
          .getTallyStatus();
      Set tallySet;
      switch (tally) {
        case "agree":
          tallySet = tallyStatus.getAgreedUrls();
          break;
        case "disagree":
          tallySet = tallyStatus.getDisagreedUrls();
          break;
        case "error":
          tallySet = tallyStatus.getErrorUrls().keySet();
          break;
        case "noQuorum":
          tallySet = tallyStatus.getNoQuorumUrls();
          break;
        case "tooClose":
          tallySet = tallyStatus.getTooCloseUrls();
          break;
        default:
          tallySet = new HashSet<String>();
      }
      if (tallySet != null) {
        Page<String> strPage = new Page<>(tallySet, page, size, baseLink);
        UrlPager pager = getUrlPager(strPage);
        return new ResponseEntity<>(pager, strPage.getPageHeaders(),
            HttpStatus.OK);

      }
    }
    return new ResponseEntity<>(new UrlPager(), HttpStatus.NOT_FOUND);
  }
  /*  -------------------Poller methods. --------------------------- */

  /**
   * Get the Polls for which we are the poller.
   *
   * @param page the page number of the paged results
   * @param size the size of the page.
   * @return A PollPager used to page in the PollerSummary objects.
   * @see PollsApi#getPollsAsPoller
   */
  public ResponseEntity<PollerPager> getPollsAsPoller(Integer size,
      Integer page) {
    if (logger.isDebugEnabled()) {
      logger.debug(
          "request for  a page " + page + " of voter polls with page size "
              + size);
    }
    PollManager pm = getPollManager();
    Collection<V3Poller> pollers = pm.getV3Pollers();
    String baseLink = request.getRequestURI();
    Page<V3Poller> pollerPage = new Page<>(pollers, size, page, baseLink);
    PollerPager pager = new PollerPager();
    // The page description.
    PageDesc desc = getPageDesc(pollerPage);
    pager.setPageDesc(desc);
    if (pollerPage.hasContent()) {
      List<V3Poller> pollerList = pollerPage.getPageContent();
      for (V3Poller poll : pollerList) {
        pager.addPollsItem(summarizePollerPoll(poll));
      }
    }
    return new ResponseEntity<>(pager, pollerPage.getPageHeaders(),
        HttpStatus.OK);
  }


  /*  -------------------Voter methods. --------------------------- */

  /**
   * Get the Polls for which we are only a voter.
   *
   * @param page the page number of the paged results
   * @param size the size of the page.
   * @return A VoterPager used to page in the VoterSummary objects.
   * @see PollsApi#getPollsAsVoter
   */
  public ResponseEntity<VoterPager> getPollsAsVoter(Integer size,
      Integer page) {
    if (logger.isDebugEnabled()) {
      logger.debug(
          "request for  a page " + page + " of voter polls with page size "
              + size);
    }
    PollManager pm = getPollManager();
    Collection<V3Voter> voters = pm.getV3Voters();
    String baseLink = request.getRequestURI();
    Page<V3Voter> voterPage = new Page<>(voters, size, page, baseLink);
    VoterPager pager = new VoterPager();
    // The page description.
    PageDesc desc = new PageDesc();
    desc.setTotal(voterPage.getTotal());
    desc.setSize(voterPage.getPageSize());
    desc.setPage(voterPage.getPageNum());
    desc.setNextPage(voterPage.getNextLink());
    desc.setPrevPage(voterPage.getPrevLink());
    pager.setPageDesc(desc);
    // The page content.
    if (voterPage.hasContent()) {
      List<V3Voter> pollerList = voterPage.getPageContent();
      for (V3Voter poll : pollerList) {
        pager.addPollsItem(summarizeVoterPoll((poll)));
      }
    }
    return new ResponseEntity<>(pager, HttpStatus.OK);
  }

  /* ------------------ DTO Mappings ---------------------- */

  /**
   * Convert a Poller Service  DTO into a PollManager PollSpec.
   *
   * @param au the ArchivalUnit for the auId in request
   * @param spec the CachedUriSetSpec that defines the polls scope.
   * @return a PollManager PollSpec.
   */
  private PollSpec pollSpecFromDesc(ArchivalUnit au, CachedUriSetSpec spec) {
    if (spec == null) {
      CachedUrlSet cus = au.getAuCachedUrlSet();
      return new PollSpec(cus, org.lockss.poller.Poll.V3_POLL);
    }
    else {
      return new PollSpec(au.getAuId(), spec.getUrlPrefix(),
          spec.getLowerBound(),
          spec.getUpperBound(), org.lockss.poller.Poll.V3_POLL);
    }
  }

  /**
   * Covert a PollManager PollSpec to a PollDesc
   *
   * @param pollSpec the PollSpec to convert.
   * @return a PollDesc DTO.
   */
  private PollDesc pollDescFromSpec(PollSpec pollSpec) {
    PollDesc pollDesc = new PollDesc();
    pollDesc.setAuId(pollSpec.getAuId());
    CachedUriSetSpec cuss = new CachedUriSetSpec();
    cuss.setUrlPrefix(pollSpec.getUrl());
    cuss.setUpperBound(pollSpec.getUprBound());
    cuss.setLowerBound(pollSpec.getLwrBound());
    pollDesc.setCuSetSpec(cuss);
    pollDesc.setPollType(pollSpec.getPollType());
    pollDesc.setProtocol(pollSpec.getProtocolVersion());
    pollDesc.setVariant(
        VariantEnum.fromValue(pollSpec.getPollVariant().shortName()));
    return pollDesc;
  }

  /**
   * Summarize a PollManager Poller Poll as a PollerSummary DTO
   *
   * @param inPoll the poll to summarize
   * @return a new PollerSummary DTO
   */
  private PollerSummary summarizePollerPoll(Poll inPoll) {
    PollerSummary summary = new PollerSummary();
    if (inPoll instanceof V3Poller) {
      V3Poller v3poller = (V3Poller) inPoll;
      PollerStateBean psb = v3poller.getPollerStateBean();
      summary.setPollKey(v3poller.getKey());
      summary.setAuId(v3poller.getAu().getAuId());
      summary.setStatus(V3Poller.POLLER_STATUS_STRINGS[v3poller.getStatus()]);
      summary.setStart(v3poller.getCreateTime());
      summary.setVariant(v3poller.getPollVariant().shortName());
      summary.setDeadline(v3poller.getDuration());
      summary.setPollEnd(psb.getPollEnd());
      summary.setParticipants(v3poller.getParticipants().size());
      TallyStatus ts = psb.getTallyStatus();
      if (ts != null) {
        summary.setNumTalliedUrls(ts.getTalliedUrlCount());
        summary.setNumAgreeUrls(ts.getAgreedUrlCount());
        summary.setNumHashErrors(ts.getErrorUrlCount());
      }
      summary.setNumCompletedRepairs(v3poller.getCompletedRepairs().size());
      summary.setDetailLink(makeDetailLink("poller/" + psb.getPollKey()));
    }
    return summary;
  }

  /**
   * Summarize a PollManager Voter Poll as a VoterSummary DTO
   *
   * @param inPoll the poll to summarize
   * @return a new VoterSummary DTO
   */
  private VoterSummary summarizeVoterPoll(Poll inPoll) {
    VoterSummary summary = new VoterSummary();
    if (inPoll instanceof V3Voter) {
      V3Voter v3Voter = (V3Voter) inPoll;
      VoterUserData userData = v3Voter.getVoterUserData();
      summary.setAuId(v3Voter.getAu().getAuId());
      summary.setCaller(v3Voter.getPollerId().getIdString());
      summary.setDeadline(v3Voter.getDeadline().getExpirationTime());
      summary.setPollKey(v3Voter.getKey());
      summary.setStart(v3Voter.getCreateTime());
      summary.setStatus(v3Voter.getStatusString());
      summary.setDetailLink(makeDetailLink("voter/" + userData.getPollKey()));
    }
    return summary;
  }


  /**
   * Convert a PollManager Poll object into a long PollDetail DTO.
   *
   * @param poll the poll to convert.
   * @return a PollDetail DTO
   */
  private PollerDetail detailPoll(Poll poll) {
    PollerDetail detail = new PollerDetail();
    if (poll instanceof V3Poller) {
      V3Poller v3poller = (V3Poller) poll;
      PollerStateBean psb = v3poller.getPollerStateBean();
      // object
      PollDesc desc = pollDescFromSpec(poll.getPollSpec());
      desc.setModulus(psb.getModulus());
      detail.setPollDesc(desc);
      detail.setPollerId(psb.getPollerId().getIdString());
      detail.setStatus(V3Poller.POLLER_STATUS_STRINGS[psb.getStatus()]);
      detail.setPollKey(psb.getPollKey());
      detail.setCreateTime(psb.getCreateTime());
      detail.setDuration(psb.getDuration());
      detail.setDeadline(psb.getPollDeadline());
      detail.setOuterCircleTarget(psb.getOuterCircleTarget());
      detail.setHashAlgorithm(psb.getHashAlgorithm());
      detail.setVoteMargin(psb.getVoteMargin());
      detail.setVoteDeadline(psb.getVoteDeadline());
      detail.setPollEnd(psb.getPollEnd());
      detail.setQuorum(psb.getQuorum());
      detail.setErrorDetails(psb.getErrorDetail());
      detail.setVoteDuration(psb.getVoteDuration());
      for (PeerIdentity peerId : psb.getNoAuPeers()) {
        detail.addNoAuPeersItem(peerId.getIdString());
      }
      for (ParticipantUserData participantData : v3poller.getParticipants()) {
        detail.addVotedPeersItem(
            peerDataFromParticipantData(participantData, psb.getPollKey()));
      }
      TallyData tallyData = tallyDataFromTallyStatus(psb.getTallyStatus(),
          psb.getPollKey());
      detail.setTally(tallyData);
      RepairQueue repairQueue = repairQueueFromDataRepairQueue(
          psb.getRepairQueue(),
          psb.getPollKey());
      detail.setRepairQueue(repairQueue);
    }
    return detail;
  }

  /**
   * Given a link and a page number return a page description
   *
   * @param page the current page
   * @return a PageDesc.
   */
  private PageDesc getPageDesc(Page page) {
    PageDesc desc = new PageDesc();
    desc.setTotal(page.getTotal());
    desc.setSize(page.getPageSize());
    desc.setPage(page.getPageNum());
    desc.setNextPage(page.getNextLink());
    desc.setPrevPage(page.getPrevLink());
    return desc;
  }

  /**
   * Convert a PollManager V3Voter Poll into a VoterDetail
   *
   * @param poll the poll to convert
   * @return the VoterDetail.
   */
  private VoterDetail detailVoterPoll(Poll poll) {
    VoterDetail detail = new VoterDetail();
    if (poll instanceof V3Voter) {
      V3Voter v3voter = (V3Voter) poll;
      PollDesc desc = pollDescFromSpec(v3voter.getPollSpec());
      VoterUserData vud = v3voter.getVoterUserData();
      desc.setModulus(vud.getModulus());
      detail.setPollDesc(desc);
      detail.setPollerId(v3voter.getPollerId().getIdString());
      detail.setCallerId(v3voter.getCallerID().getIdString());
      detail.setStatus(vud.getStatusString());
      detail.setPollKey(vud.getPollKey());
      detail.setCreateTime(vud.getCreateTime());
      detail.setDuration(vud.getDuration());
      detail.setDeadline(vud.getDeadline());
      detail.setHashAlgorithm(vud.getHashAlgorithm());
      detail.setVoteDeadline(vud.getVoteDeadline());
      detail.setErrorDetails(vud.getErrorDetail());
      if (v3voter.getStatus() == V3Voter.STATUS_COMPLETE) {
        if (vud.hasReceivedHint()) {
          detail.setAgreement(vud.getAgreementHint());
        }
        if (vud.hasReceivedWeightedHint()) {
          detail.setWtAgreement(vud.getWeightedAgreementHint());
        }
        if (vud.hasReceivedSymmetricAgreement()) {
          detail.setSymmetricAgreement(vud.getSymmetricAgreement());
        }
        if (vud.hasReceivedSymmetricWeightedAgreement()) {
          detail.setWtSymmetricAgreement(vud.getSymmetricAgreement());
        }
      }
      detail.setPollerNonce(ByteArray.toBase64(vud.getPollerNonce()));
      detail.setVoterNonce(ByteArray.toBase64((vud.getVoterNonce())));
      if (vud.isSymmetricPoll()) {
        detail.setVoter2Nonce(ByteArray.toBase64(vud.getVoterNonce2()));
        if (v3voter.getStatus() == V3Voter.STATUS_COMPLETE) {
          detail.setNumAgree(vud.getNumAgreeUrl());
          detail.setNumDisagree(vud.getNumDisagreeUrl());
          detail.setNumPollerOnly(vud.getNumPollerOnlyUrl());
          detail.setNumVoterOnly(vud.getNumVoterOnlyUrl());

        }
      }
    }
    return detail;
  }

  /**
   * Convert a PollManager's ParticipantUserData object to a PeerData DTO.
   *
   * @param voter the voter data to convert
   * @param pollKey the poll key to use for lin construction.
   * @return a PeerData data transfer object.
   */
  private PeerData peerDataFromParticipantData(ParticipantUserData voter,
      String pollKey) {
    PeerData peerData = new PeerData();
    peerData.setPeerId(voter.getVoterId().getIdString());
    peerData.setStatus(voter.getStatusString());
    peerData.setAgreement(voter.getPercentAgreement());
    ParticipantUserData.VoteCounts voteCounts = voter.getVoteCounts();
    peerData.setNumAgree(voteCounts.getAgreedVotes());
    peerData.setNumDisagree(voteCounts.getDisagreedVotes());
    peerData.setNumPollerOnly(voteCounts.getPollerOnlyVotes());
    peerData.setNumVoterOnly(voteCounts.getVoterOnlyVotes());
    peerData.setBytesHashed(voter.getBytesHashed());
    peerData.setBytesRead(voter.getBytesRead());
    peerData.setWtAgreement(voteCounts.getWeightedPercentAgreement());
    peerData.setWtNumDisagree(voteCounts.getWeightedDisagreedVotes());
    peerData.setWtNumPollerOnly(voteCounts.getWeightedPollerOnlyVotes());
    peerData.setWtNumVoterOnly(voteCounts.getWeightedVoterOnlyVotes());
    PsmInterp interp = voter.getPsmInterp();
    if (interp != null) {
      PsmState state = interp.getCurrentState();
      if (state != null) {
        peerData.setState(state.getName());
        long when = interp.getLastStateChange();
        if (when > 0) {
          peerData.setLastStateChange(when);
        }
      }
    }
    String peerId = peerData.getPeerId();
    peerData.setAgreeLink(makePeerLink(pollKey, peerId, "agree"));
    peerData.setDisagreeLink(makePeerLink(pollKey, peerId, "disagree"));
    peerData.setPollerOnlyLink(makePeerLink(pollKey, peerId, "pollerOnly"));
    peerData.setVoterOnlyLink(makePeerLink(pollKey, peerId, "voterOnly"));
    return peerData;
  }

  /**
   * Convert a PollManager's RepairQueue object into a RepairQueue DTO
   *
   * @param inQueue the RepairQueue to convert
   * @param pollKey the key of the repair queue.
   * @return a PollService Repair Queue
   */
  private RepairQueue repairQueueFromDataRepairQueue(
      PollerStateBean.RepairQueue inQueue,
      String pollKey) {
    RepairQueue outQueue = new RepairQueue();
    outQueue.setNumActive(inQueue.getActiveRepairs().size());
    outQueue.setNumPending(inQueue.getPendingRepairs().size());
    outQueue.setNumCompleted(inQueue.getCompletedRepairs().size());
    outQueue.setActiveLink(makeRepairQLink(pollKey, "active"));
    outQueue.setCompletedLink(makeRepairQLink(pollKey, "completed"));
    outQueue.setPendingLink(makeRepairQLink(pollKey, "pending"));
    return outQueue;
  }

  /**
   * Convert a  PollManager's TallyStatus object into a TallyData DTO
   *
   * @param tallyStatus The TallyStatus object to convert
   * @param pollKey the key to use for the links.
   * @return a new TallyData object
   */
  private TallyData tallyDataFromTallyStatus(TallyStatus tallyStatus,
      String pollKey) {
    TallyData tallyData = new TallyData();
    tallyData.setNumAgree(tallyStatus.getAgreedUrlCount());
    tallyData.setNumDisagree(tallyStatus.getDisgreedUrlCount());
    tallyData.setNumNoQuorum(tallyStatus.getNoQuorumUrlCount());
    tallyData.setNumTooClose(tallyStatus.getTooCloseUrlCount());
    tallyData.setNumError(tallyStatus.getErrorUrlCount());
    tallyData.setWtAgreed(tallyStatus.getWeightedAgreedCount());
    tallyData.setWtDisagreed(tallyStatus.getWeightedDisagreedCount());
    tallyData.setWtNoQuorum(tallyStatus.getWeightedNoQuorumCount());
    tallyData.setWtTooClose(tallyStatus.getWeightedTooCloseCount());
    tallyData.setAgreeLink(makeTallyLink(pollKey, "agree"));
    tallyData.setDisagreeLink(makeTallyLink(pollKey, "disagree"));
    tallyData.setNoQuorumLink(makeTallyLink(pollKey, "noQuorum"));
    tallyData.setTooCloseLink(makeTallyLink(pollKey, "tooClose"));
    tallyData.setErrorLink(makeTallyLink(pollKey, "error"));
    return tallyData;
  }

  /**
   * Make a link to the details for a poll.
   *
   * @param pollKey the key to the poll.
   * @return a link to the polls detailed data.
   */
  private LinkDesc makeDetailLink(String pollKey) {
    try {
      // build a path element: "/polls/{pollKey}/"
      String prefix = UrlUtil.getUrlPrefix(request.getRequestURI());
      LinkDesc ldesc = new LinkDesc();
      ldesc.setLink(prefix + "/polls/" + pollKey + "/details");
      return ldesc;
    }
    catch (MalformedURLException e) {
      logger.error(DETAIL_UNAVAILABLE);
      // throw or ErrorDesc.
    }
    return null;
  }

  /**
   * Make a link to the tally details for a poll.
   *
   * @param pollKey the key for the poll
   * @param tallyType the type of tally data to return.
   * @return a new Link description object.
   */
  private LinkDesc makeTallyLink(String pollKey, String tallyType) {
    try {
      // build a path element: "/polls/{pollKey}/tally?tally=type"
      String prefix = UrlUtil.getUrlPrefix(request.getRequestURI());
      LinkDesc ldesc = new LinkDesc();
      ldesc.setLink(prefix + "/polls/" + pollKey + "/tally?tally=" + tallyType);
      return ldesc;
    }
    catch (MalformedURLException e) {
      logger.error(DETAIL_UNAVAILABLE);
      // throw or ErrorDesc.
    }
    return null;
  }

  /**
   * Make a link to the repair queue details for a poll.
   *
   * @param pollKey the key for the poll.
   * @param repairType the repair data to provide.
   * @return a new Link description object.
   */
  private LinkDesc makeRepairQLink(String pollKey, String repairType) {
    try {
      //  build a path element: /polls/{pollKey}/repairs?repair=type
      String prefix = UrlUtil.getUrlPrefix(request.getRequestURI());
      LinkDesc ldesc = new LinkDesc();
      ldesc.setLink(
          prefix + "/polls/" + pollKey + "/repairs?repair=" + repairType);
      return ldesc;
    }
    catch (MalformedURLException e) {
      logger.error(DETAIL_UNAVAILABLE);
    }
    return null;
  }

  /**
   * Make a link to the tally details for a peer.
   *
   * @param pollKey the pollKey for the link.
   * @param peerId the peerId for the link.
   * @param tallyType the tally data to provide.
   * @return a new Link description object.
   */
  private LinkDesc makePeerLink(String pollKey, String peerId,
      String tallyType) {
    // /polls/{pollKey}/peer/{peerId}?tally=
    try {
      String prefix = UrlUtil.getUrlPrefix(request.getRequestURI());
      LinkDesc ldesc = new LinkDesc();
      ldesc.setLink(prefix + "/polls/" + pollKey + "/peer/" + peerId + "?tally="
          + tallyType);
      return ldesc;
    }
    catch (MalformedURLException e) {
      logger.error(DETAIL_UNAVAILABLE);
    }
    return null;
  }

  /**
   * Find the user data for a voting peer.
   *
   * @param peerId the Peer Id of the voting peer
   * @param participants the list of voting peers
   * @return the ParticipantUserData for the Peer or null if not found.
   */
  private ParticipantUserData userDataForPeer(String peerId,
      List<ParticipantUserData> participants) {
    if (peerId == null || participants == null || participants.isEmpty()) {
      return null;
    }
    for (ParticipantUserData userData : participants) {
      if (userData.getVoterId().getIdString().equals(peerId)) {
        return userData;
      }
    }
    return null;
  }


  /* ------------------------------------------------------------------------
      Lockss App integration Support
     ------------------------------------------------------------------------
  */

  /**
   * Provides the Lockss daemon instance
   *
   * @return the LockssDaemon
   */
  LockssDaemon getLockssDaemon() {
    if (theDaemon == null) {
      theDaemon = LockssDaemon.getLockssDaemon();
    }
    return theDaemon;
  }

  /**
   * Provides the poll manager.
   *
   * @return the current Lockss PollManager.
   */
  PollManager getPollManager() {
    if (pollManager == null) {
      pollManager = getLockssDaemon().getPollManager();
    }
    return pollManager;
  }

  /**
   * Provides the plugin manager.
   *
   * @return the current Lockss PluginManager.
   */
  PluginManager getPluginManager() {
    if (pluginManager == null) {
      pluginManager = getLockssDaemon().getPluginManager();
    }
    return pluginManager;
  }
}
