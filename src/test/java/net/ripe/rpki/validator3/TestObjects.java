package net.ripe.rpki.validator3;

import net.ripe.rpki.validator3.domain.TrustAnchor;

import java.util.Arrays;

public class TestObjects {

    private static final String RIPE_NCC_TRUST_ANCHOR_SUBJECT_PUBLIC_KEY_INFO = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0URYSGqUz2myBsOzeW1jQ6NsxNvlLMyhWknvnl8NiBCs/T/S2XuNKQNZ+wBZxIgPPV2pFBFeQAvoH/WK83HwA26V2siwm/MY2nKZ+Olw+wlpzlZ1p3Ipj2eNcKrmit8BwBC8xImzuCGaV0jkRB0GZ0hoH6Ml03umLprRsn6v0xOP0+l6Qc1ZHMFVFb385IQ7FQQTcVIxrdeMsoyJq9eMkE6DoclHhF/NlSllXubASQ9KUWqJ0+Ot3QCXr4LXECMfkpkVR2TZT+v5v658bHVs6ZxRD1b6Uk1uQKAyHUbn/tXvP8lrjAibGzVsXDT2L0x4Edx+QdixPgOji3gBMyL2VwIDAQAB";

    public static TrustAnchor newTrustAnchor() {
        TrustAnchor trustAnchor = new TrustAnchor();
        trustAnchor.setName("trust anchor");
        trustAnchor.setLocations(Arrays.asList("rsync://rpki.test/trust-anchor.cer"));
        trustAnchor.setSubjectPublicKeyInfo(RIPE_NCC_TRUST_ANCHOR_SUBJECT_PUBLIC_KEY_INFO);
        return trustAnchor;
    }
}
