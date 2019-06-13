/*

Copyright (c) 2019 Board of Trustees of Leland Stanford Jr. University,
all rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 */
package org.lockss.laaws.mdq.impl;

import java.util.ConcurrentModificationException;
import javax.servlet.http.HttpServletRequest;
import org.lockss.app.LockssApp;
import org.lockss.laaws.mdq.api.MetadataApiDelegate;
import org.lockss.laaws.mdq.model.AuMetadataPageInfo;
import org.lockss.laaws.mdq.model.PageInfo;
import org.lockss.laaws.status.model.ApiStatus;
import org.lockss.log.L4JLogger;
import org.lockss.metadata.ItemMetadataContinuationToken;
import org.lockss.metadata.ItemMetadataPage;
import org.lockss.metadata.query.MetadataQueryManager;
import org.lockss.spring.status.SpringLockssBaseApiController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Service for access to the metadata of an AU.
 */
@Service
public class MetadataApiServiceImpl
    implements MetadataApiDelegate {
  private static final L4JLogger log = L4JLogger.getLogger();

  @Autowired
  private HttpServletRequest request;

  /**
   * Provides the full metadata stored for an AU given the AU identifier or a
   * pageful of the metadata defined by the continuation token and size.
   * 
   * @param auid
   *          A String with the AU identifier.
   * @param limit
   *          An Integer with the maximum number of AU metadata items to be
   *          returned.
   * @param continuationToken
   *          A String with the continuation token of the next page of metadata
   *          to be returned.
   * @return a {@code ResponseEntity<AuMetadataPageInfo>} with the metadata.
   */
  @Override
  public ResponseEntity<AuMetadataPageInfo> getMetadataAusAuid(String auid,
      Integer limit, String continuationToken) {
    log.debug2("auid = {}", () -> auid);
    log.debug2("limit = {}", () -> limit);
    log.debug2("continuationToken = {}", () -> continuationToken);

    // Validation of requested page size.
    if (limit == null || limit.intValue() < 0) {
      String message = "Limit of requested items must be a non-negative "
	  + "integer; it was '" + limit + "'";
      log.warn(message);
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    // Parse the request continuation token.
    ItemMetadataContinuationToken imct = null;

    try {
      imct = new ItemMetadataContinuationToken(continuationToken);
    } catch (IllegalArgumentException iae) {
      String message = "Invalid continuation token '" + continuationToken + "'";
      log.warn(message, iae);
      return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
    }

    try {
      // Get the pageful of results.
      ItemMetadataPage itemsPage = LockssApp.getManagerByTypeStatic(
	  MetadataQueryManager.class).getAuMetadataDetail(auid, limit, imct);
      log.trace("itemsPage = {}", () -> itemsPage);

      AuMetadataPageInfo result = new AuMetadataPageInfo();
      PageInfo pi = new PageInfo();
      result.setPageInfo(pi);

      StringBuffer curLinkBuffer = new StringBuffer(
	  request.getRequestURL().toString()).append("?limit=").append(limit);

      if (continuationToken != null) {
	curLinkBuffer.append("&continuationToken=").append(continuationToken);
      }

      log.trace("curLink = {}", () -> curLinkBuffer.toString());

      pi.setCurLink(curLinkBuffer.toString());
      pi.setResultsPerPage(itemsPage.getItems().size());

      // Check whether there is a response continuation token.
      if (itemsPage.getContinuationToken() != null) {
	// Yes.
	pi.setContinuationToken(itemsPage.getContinuationToken()
	    .toWebResponseContinuationToken());

	String nextLink = request.getRequestURL().toString() + "?limit=" + limit
	    + "&continuationToken=" + pi.getContinuationToken();
	log.trace("nextLink = {}", () -> nextLink);

	pi.setNextLink(nextLink);
      }

      result.setItems(itemsPage.getItems());

      log.debug2("result = {}", () -> result);
      return new ResponseEntity<AuMetadataPageInfo>(result, HttpStatus.OK);
    } catch (ConcurrentModificationException cme) {
      String message =
	  "Pagination conflict for auid '" + auid + "': " + cme.getMessage();
      log.warn(message, cme);
      return new ResponseEntity<>(HttpStatus.CONFLICT);
    } catch (IllegalArgumentException iae) {
      String message = "No Archival Unit found for auid '" + auid + "'";
      log.warn(message, iae);
      return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    } catch (Exception e) {
      String message = "Cannot getMetadataAusAuid() for auid '" + auid + "'";
      log.error(message, e);
      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }
}
