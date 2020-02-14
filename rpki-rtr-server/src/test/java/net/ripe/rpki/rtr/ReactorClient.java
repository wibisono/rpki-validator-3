package net.ripe.rpki.rtr;

import net.ripe.rpki.rtr.adapter.netty.PduCodec;
import net.ripe.rpki.rtr.domain.SerialNumber;
import net.ripe.rpki.rtr.domain.pdus.Pdu;
import net.ripe.rpki.rtr.domain.pdus.ResetQueryPdu;
import net.ripe.rpki.rtr.domain.pdus.SerialQueryPdu;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.tcp.TcpClient;

import static net.ripe.rpki.rtr.domain.pdus.ProtocolVersion.V0;

public class ReactorClient {

    public static void main(String[] args) {

        final short sessionId = (short) ( 0);
        final SerialNumber serial = SerialNumber.of(0);

        Mono<Pdu> serialQueryPdu = Mono.just(SerialQueryPdu.of(V0, sessionId, serial));

        Mono<Pdu> resetQueryPdu = Mono.just(ResetQueryPdu.of(V0));
        Connection connection =
                TcpClient.create()
                        .wiretap(true)
                        .host("localhost")
                        .port(8323)
                        .doOnConnected(conn->conn.addHandler(new PduCodec()))
                        .handle((inboud, outbound) -> outbound.sendObject(resetQueryPdu).neverComplete())
                        .connectNow();

        connection.inbound().receiveObject().then().doOnNext(s-> System.out.println("Receiving = " + s)).subscribe();
        connection.bind();
        connection.onDispose().block();
    }
}
