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
package net.ripe.rpki.validator3.storage.encoding.custom.validation;

import net.ripe.rpki.validator3.storage.data.Ref;
import net.ripe.rpki.validator3.storage.data.TrustAnchor;
import net.ripe.rpki.validator3.storage.data.validation.TrustAnchorValidationRun;
import net.ripe.rpki.validator3.storage.encoding.Coder;
import net.ripe.rpki.validator3.storage.encoding.custom.Coders;
import net.ripe.rpki.validator3.storage.encoding.custom.Encoded;
import net.ripe.rpki.validator3.storage.encoding.custom.RefCoder;
import net.ripe.rpki.validator3.storage.encoding.custom.Tags;

import java.util.Map;

public class TAValidationRunCoder implements Coder<TrustAnchorValidationRun> {

    private final static short TA_TAG = Tags.unique(101);
    private final static short URI_TAG = Tags.unique(102);

    private final static RefCoder<TrustAnchor> taRefCoder = new RefCoder<>();

    @Override
    public byte[] toBytes(TrustAnchorValidationRun validationRun) {
        final Encoded encoded = new Encoded();
        ValidationRunCoder.toBytes(validationRun, encoded);
        encoded.appendNotNull(TA_TAG, validationRun.getTrustAnchor(), taRefCoder::toBytes);
        encoded.appendNotNull(URI_TAG, validationRun.getTrustAnchorCertificateURI(), Coders::toBytes);
        return encoded.toByteArray();
    }

    @Override
    public TrustAnchorValidationRun fromBytes(byte[] bytes) {
        Map<Short, byte[]> content = Encoded.fromByteArray(bytes).getContent();
        final Ref<TrustAnchor> trustAnchorRef = taRefCoder.fromBytes(content.get(TA_TAG));
        final String uri = Coders.toString(content.get(URI_TAG));
        final TrustAnchorValidationRun validationRun = new TrustAnchorValidationRun(trustAnchorRef, uri);
        ValidationRunCoder.fromBytes(content, validationRun);
        return validationRun;
    }
}
