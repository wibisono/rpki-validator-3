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
package net.ripe.rpki.validator3.domain.validation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject;
import net.ripe.rpki.commons.crypto.cms.roa.RoaCms;
import net.ripe.rpki.commons.crypto.cms.roa.RoaPrefix;
import net.ripe.rpki.commons.crypto.x509cert.X509CertificateUtil;
import net.ripe.rpki.commons.crypto.x509cert.X509RouterCertificate;
import net.ripe.rpki.validator3.api.Paging;
import net.ripe.rpki.validator3.api.SearchTerm;
import net.ripe.rpki.validator3.api.Sorting;
import net.ripe.rpki.validator3.domain.ValidatedRoaPrefix;
import net.ripe.rpki.validator3.storage.Storage;
import net.ripe.rpki.validator3.storage.Tx;
import net.ripe.rpki.validator3.storage.data.Key;
import net.ripe.rpki.validator3.storage.data.Ref;
import net.ripe.rpki.validator3.storage.data.RpkiObject;
import net.ripe.rpki.validator3.storage.data.TrustAnchor;
import net.ripe.rpki.validator3.storage.stores.RpkiObjects;
import net.ripe.rpki.validator3.storage.stores.TrustAnchors;
import net.ripe.rpki.validator3.storage.stores.ValidationRuns;
import net.ripe.rpki.validator3.util.Locks;
import net.ripe.rpki.validator3.util.Time;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Component
@Slf4j
public class ValidatedRpkiObjects {

    private final List<Consumer<Collection<RoaPrefixesAndRouterCertificates>>> listeners = new ArrayList<>();

    private Map<Long, RoaPrefixesAndRouterCertificates> validatedObjectsByTrustAnchor = new HashMap<>();

    @Autowired
    private RpkiObjects rpkiObjects;

    @Autowired
    private TrustAnchors trustAnchors;

    @Autowired
    private ValidationRuns validationRuns;

    @Autowired
    private Storage storage;

    private ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();

    @PostConstruct
    private void initialize() {
        Long t = Time.timed(() -> {
            final List<TrustAnchor> trustAnchorList = storage.readTx(tx -> trustAnchors.findAll(tx));
            trustAnchorList.parallelStream().forEach(ta ->
                    storage.readTx0(tx -> {
                        TrustAnchorData trustAnchorData = TrustAnchorData.of(ta.getId().asLong(), ta.getName());
                        final Accumulator validatedObjects = new Accumulator(trustAnchorData);
                        validationRuns.findLatestSuccessfulCaTreeValidationRun(tx, ta).ifPresent(vr -> {
                            final Set<Key> associatedPks = validationRuns.findAssociatedPks(tx, vr);
                            Stream<RpkiObject> roaStream = streamByType(tx, associatedPks, RpkiObject.Type.ROA);
                            Stream<RpkiObject> routerCertStream = streamByType(tx, associatedPks, RpkiObject.Type.ROUTER_CER);
                            Stream.concat(roaStream, routerCertStream).forEach(rpkiObject -> {
                                SortedSet<String> locations = rpkiObjects.getLocations(tx, rpkiObject.key());
                                if (locations.isEmpty()) {
                                    log.warn("RPKI object {} without location, skipping", rpkiObject.key());
                                    return;
                                }

                                Optional<CertificateRepositoryObject> maybeObject = rpkiObject.get(CertificateRepositoryObject.class, locations.first());
                                if (!maybeObject.isPresent()) {
                                    log.warn("Unparsable RPKI object {}, skipping", rpkiObject.key());
                                    return;
                                }
                                validatedObjects.add(rpkiObject.key(), maybeObject.get(), ImmutableSortedSet.copyOf(locations));
                            });
                            updateByKey(vr.getTrustAnchor(), validatedObjects);
                        });
                    }));
        });
        log.info("Validated objects cache initialised in {}ms", t);
    }

    void updateByKey(Ref<TrustAnchor> trustAnchor, Accumulator validatedObjects) {
        Locks.locked(dataLock.writeLock(), () -> {
            log.info("updating validation objects cache for trust anchor {} with {} ROA prefixes and {} router certificates",
                    trustAnchor,
                    validatedObjects.getValidatedRoaPrefixes().size(),
                    validatedObjects.getRouterCertificates().size()
            );
            validatedObjectsByTrustAnchor.put(
                    trustAnchor.key().asLong(),
                    RoaPrefixesAndRouterCertificates.of(
                            ImmutableSet.copyOf(validatedObjects.getValidatedRoaPrefixes()),
                            ImmutableSet.copyOf(validatedObjects.getRouterCertificates())
                    )
            );
        });
        notifyListeners();
    }

    private Stream<RpkiObject> streamByType(Tx.Read tx, Collection<Key> rpkiObjectsKeys, RpkiObject.Type type) {
        final Set<Key> byType = rpkiObjects.getPkByType(tx, type);
        return rpkiObjectsKeys.stream()
            .filter(byType::contains)
            .map(k -> rpkiObjects.get(tx, k))
            .filter(Optional::isPresent)
            .map(Optional::get);
    }

    public void remove(TrustAnchor trustAnchor) {
        long trustAnchorId = trustAnchor.key().asLong();
        Locks.locked(dataLock.writeLock(), () -> validatedObjectsByTrustAnchor.remove(trustAnchorId));
        notifyListeners();
    }

    public ValidatedObjects<ValidatedRoaPrefix> findCurrentlyValidatedRoaPrefixes() {
        return findCurrentlyValidatedRoaPrefixes(null, null, null);
    }

    public ValidatedObjects<ValidatedRoaPrefix> findCurrentlyValidatedRoaPrefixes(SearchTerm searchTerm, Sorting sorting, Paging paging) {
        if (paging == null) {
            paging = Paging.of(0L, Long.MAX_VALUE);
        }
        if (sorting == null) {
            sorting = Sorting.of(Sorting.By.TA, Sorting.Direction.ASC);
        }

        Sorting finalSorting = sorting;
        Paging finalPaging = paging;
        return Locks.locked(dataLock.readLock(), () ->
            ValidatedObjects.of(
                countRoaPrefixes(searchTerm),
                findRoaPrefixes(searchTerm, finalSorting, finalPaging)
            ));
    }

    public ValidatedObjects<RouterCertificate> findCurrentlyValidatedRouterCertificates() {
        return Locks.locked(dataLock.readLock(), () ->
            ValidatedObjects.of(
                countRouterCertificates(),
                findRouterCertificates()
            ));
    }

    public void addListener(Consumer<Collection<RoaPrefixesAndRouterCertificates>> listener) {
        Locks.locked(dataLock.writeLock(), () -> {
            listeners.add(listener);
            listener.accept(validatedObjectsByTrustAnchor.values());
        });
    }

    @Value(staticConstructor = "of")
    public static class ValidatedObjects<T> {
        long totalCount;
        Stream<T> objects;
    }

    @Value(staticConstructor = "of")
    public static class RoaPrefixesAndRouterCertificates {
        ImmutableSet<ValidatedRoaPrefix> roaPrefixes;
        ImmutableSet<RouterCertificate> routerCertificates;
    }

    @Value(staticConstructor = "of")
    public static class TrustAnchorData {
        long id;
        String name;
    }

    @Value(staticConstructor = "of")
    public static class RouterCertificate {
        TrustAnchorData trustAnchor;
        ImmutableList<String> asn;
        String subjectKeyIdentifier;
        String subjectPublicKeyInfo;
    }

    private ImmutableList<RoaPrefixesAndRouterCertificates> validatedObjects() {
        return Locks.locked(dataLock.readLock(), () ->
            ImmutableList.copyOf(validatedObjectsByTrustAnchor.values()));
    }

    private int countRouterCertificates() {
        return validatedObjects().stream().mapToInt(x -> x.getRouterCertificates().size()).sum();
    }

    private Stream<RouterCertificate> findRouterCertificates() {
        return validatedObjects()
            .stream()
            .flatMap(x -> x.getRouterCertificates().stream());
    }

    private long countRoaPrefixes(SearchTerm searchTerm) {
        return validatedObjects().stream()
            .flatMap(x -> x.getRoaPrefixes().stream())
            .filter(prefix -> searchTerm == null || searchTerm.test(prefix))
            .count();
    }

    private Stream<ValidatedRoaPrefix> findRoaPrefixes(SearchTerm searchTerm, Sorting sorting, Paging paging) {
        return validatedObjects()
            .parallelStream()
            .flatMap(x -> x.getRoaPrefixes().stream())
            .filter(prefix -> searchTerm == null || searchTerm.test(prefix))
            .sorted(sorting.comparator())
            .skip(Math.max(0, paging.getStartFrom()))
            .limit(Math.max(1, paging.getPageSize()));
    }

    private void notifyListeners() {
        Locks.locked(dataLock.readLock(), () ->
            listeners.forEach(listener -> listener.accept(validatedObjectsByTrustAnchor.values())));
    }

    public static class Accumulator {
        private final TrustAnchorData trustAnchorData;
        private final List<Key> validatedObjectKeys = Collections.synchronizedList(new ArrayList<>());
        private final List<ValidatedRoaPrefix> validatedRoaPrefixes = Collections.synchronizedList(new ArrayList<>());
        private final List<RouterCertificate> routerCertificates = Collections.synchronizedList(new ArrayList<>());

        public Accumulator(TrustAnchorData trustAnchorData) {
            this.trustAnchorData = trustAnchorData;
        }

        public void add(Key key, CertificateRepositoryObject object, ImmutableSortedSet<String> locations) {
            validatedObjectKeys.add(key);
            if (object instanceof RoaCms) {
                RoaCms roa = (RoaCms) object;
                for (RoaPrefix prefix: roa.getPrefixes()) {
                    validatedRoaPrefixes.add(ValidatedRoaPrefix.of(
                            trustAnchorData,
                            roa.getAsn().longValue(),
                            prefix.getPrefix(),
                            prefix.getMaximumLength(),
                            roa.getNotValidBefore().getMillis(),
                            roa.getNotValidAfter().getMillis(),
                            roa.getCertificate().getSerialNumber(),
                            locations
                    ));
                }
            } else if (object instanceof X509RouterCertificate) {
                final Base64.Encoder encoder = Base64.getEncoder();
                X509RouterCertificate certificate = (X509RouterCertificate) object;
                final ImmutableList<String> asns = ImmutableList.copyOf(X509CertificateUtil.getAsns(certificate.getCertificate()));
                final String ski = encoder.encodeToString(X509CertificateUtil.getSubjectKeyIdentifier(certificate.getCertificate()));
                final String pkInfo = X509CertificateUtil.getEncodedSubjectPublicKeyInfo(certificate.getCertificate());
                routerCertificates.add(RouterCertificate.of(
                        trustAnchorData,
                        asns,
                        ski,
                        pkInfo
                ));
            }
        }

        public boolean isEmpty() {
            return validatedObjectKeys.isEmpty();
        }

        public int size() {
            return validatedObjectKeys.size();
        }

        public List<Key> getKeys() {
            return validatedObjectKeys;
        }

        public void forEach(Consumer<? super Key> consumer) {
            validatedObjectKeys.forEach(consumer);
        }

        public List<ValidatedRoaPrefix> getValidatedRoaPrefixes() {
            return validatedRoaPrefixes;
        }

        public List<RouterCertificate> getRouterCertificates() {
            return routerCertificates;
        }
    }
}
