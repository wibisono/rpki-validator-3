/**
 * The BSD License
 *
 * Copyright (c) 2010-2018 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.rtr;

import io.netty.channel.embedded.EmbeddedChannel;
import net.ripe.ipresource.Asn;
import net.ripe.ipresource.IpRange;
import net.ripe.rpki.rtr.domain.RtrCache;
import net.ripe.rpki.rtr.domain.RtrClients;
import net.ripe.rpki.rtr.domain.RtrDataUnit;
import net.ripe.rpki.rtr.domain.RtrPrefix;
import net.ripe.rpki.rtr.domain.SerialNumber;
import net.ripe.rpki.rtr.domain.pdus.CacheResetPdu;
import net.ripe.rpki.rtr.domain.pdus.CacheResponsePdu;
import net.ripe.rpki.rtr.domain.pdus.EndOfDataPdu;
import net.ripe.rpki.rtr.domain.pdus.ErrorCode;
import net.ripe.rpki.rtr.domain.pdus.ErrorPdu;
import net.ripe.rpki.rtr.domain.pdus.Flags;
import net.ripe.rpki.rtr.domain.pdus.NotifyPdu;
import net.ripe.rpki.rtr.domain.pdus.Pdu;
import net.ripe.rpki.rtr.domain.pdus.ResetQueryPdu;
import net.ripe.rpki.rtr.domain.pdus.SerialQueryPdu;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RtrServerTest {

    private static final RtrPrefix AS_3333 = RtrDataUnit.prefix(Asn.parse("AS3333"), IpRange.parse("127.0.0.0/8"), 12);
    private static final RtrPrefix AS_4444 = RtrDataUnit.prefix(Asn.parse("AS4444"), IpRange.parse("127.0.0.0/8"), 12);

    private final RtrCache rtrCache = new RtrCache();
    private final RtrClients clients = new RtrClients();
    private final EmbeddedChannel channel = new EmbeddedChannel(new RtrServer.RtrClientHandler(rtrCache, clients));

    @Before
    public void setUp() throws Exception {
        updateCache(Collections.singleton(AS_3333));
    }

    @Test
    public void should_reply_with_cache_reset_is_session_id_is_different() {
        final short sessionId = (short) (rtrCache.getSessionId() + 10);
        final SerialNumber serial = SerialNumber.of(20);

        clientRequest(SerialQueryPdu.of(sessionId, serial));

        assertResponse(CacheResetPdu.of());
    }

    @Test
    public void should_reply_with_cache_response_if_session_id_the_same() {
        clientRequest(SerialQueryPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber()));

        assertResponse(
            CacheResponsePdu.of(rtrCache.getSessionId()),
            EndOfDataPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber(), 3600, 600, 7200)
        );
    }

    @Test
    public void should_reply_with_cache_reset_reset_query_pdu() {
        clientRequest(ResetQueryPdu.of());

        assertResponse(
            CacheResponsePdu.of(rtrCache.getSessionId()),
            AS_3333.toPdu(Flags.ANNOUNCEMENT),
            EndOfDataPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber(), 3600, 600, 7200)
        );
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc8210#section-8.3">No incremental update available</a>.
     */
    @Test
    public void should_reply_with_cache_reset_if_delta_not_available() {
        clientRequest(SerialQueryPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber().previous().previous()));

        assertResponse(
            CacheResetPdu.of()
        );

        clientRequest(ResetQueryPdu.of());

        assertResponse(
            CacheResponsePdu.of(rtrCache.getSessionId()),
            AS_3333.toPdu(Flags.ANNOUNCEMENT),
            EndOfDataPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber(), 3600, 600, 7200)
        );
    }

    @Test
    public void should_reply_with_cache_response_if_delta_is_available() {
        updateCache(Collections.singleton(AS_4444));

        clientRequest(SerialQueryPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber().previous()));

        assertResponse(
            CacheResponsePdu.of(rtrCache.getSessionId()),
            AS_4444.toPdu(Flags.ANNOUNCEMENT),
            AS_3333.toPdu(Flags.WITHDRAWAL),
            EndOfDataPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber(), 3600, 600, 7200)
        );
    }

    @Test
    public void should_notify_client_when_cache_is_updated() {
        clientRequest(ResetQueryPdu.of());
        assertResponse(
            CacheResponsePdu.of(rtrCache.getSessionId()),
            AS_3333.toPdu(Flags.ANNOUNCEMENT),
            EndOfDataPdu.of(rtrCache.getSessionId(), SerialNumber.of(1), 3600, 600, 7200)
        );

        Set<RtrDataUnit> singleton = Collections.singleton(AS_4444);
        updateCache(singleton);

        assertResponse(
            NotifyPdu.of(rtrCache.getSessionId(), SerialNumber.of(2))
        );
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc8210#section-8.4">Cache Has No Data Available</a>.
     */
    @Test
    public void should_send_no_data_available_until_cache_is_ready_on_reset_query() {
        rtrCache.reset();
        ResetQueryPdu request = ResetQueryPdu.of();

        clientRequest(request);

        assertResponse(
            ErrorPdu.of(ErrorCode.NoDataAvailable, request.toByteArray(), "no data available")
        );
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc8210#section-8.4">Cache Has No Data Available</a>.
     */
    @Test
    public void should_send_no_data_available_until_cache_is_ready_on_serial_query() {
        rtrCache.reset();
        SerialQueryPdu request = SerialQueryPdu.of(rtrCache.getSessionId(), rtrCache.getSerialNumber());

        clientRequest(request);

        assertResponse(
            ErrorPdu.of(ErrorCode.NoDataAvailable, request.toByteArray(), "no data available")
        );
    }

    @Test
    public void should_not_send_notify_until_after_first_client_request() {
        updateCache(Collections.singleton(AS_4444));

        assertNull(channel.readOutbound());
    }

    private void updateCache(Set<RtrDataUnit> dataUnits) {
        rtrCache.update(dataUnits).ifPresent(sn -> clients.cacheUpdated(rtrCache.getSessionId(), sn));
    }

    private void clientRequest(Pdu pdu) {
        channel.writeInbound(pdu);
    }

    private void assertResponse(Pdu... expectedResponses) {
        for (Pdu expected : expectedResponses) {
            Pdu response = channel.readOutbound();
            assertEquals(expected, response);
        }
    }
}