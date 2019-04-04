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
package net.ripe.rpki.validator3.storage.stores.impl;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.validator3.api.Paging;
import net.ripe.rpki.validator3.api.SearchTerm;
import net.ripe.rpki.validator3.api.Sorting;
import net.ripe.rpki.validator3.background.ValidationScheduler;
import net.ripe.rpki.validator3.domain.constraints.ValidLocationURI;
import net.ripe.rpki.validator3.storage.FSTCoder;
import net.ripe.rpki.validator3.storage.Lmdb;
import net.ripe.rpki.validator3.storage.data.Key;
import net.ripe.rpki.validator3.storage.data.Ref;
import net.ripe.rpki.validator3.storage.data.RpkiRepository;
import net.ripe.rpki.validator3.storage.data.TrustAnchor;
import net.ripe.rpki.validator3.storage.lmdb.IxMap;
import net.ripe.rpki.validator3.storage.lmdb.Tx;
import net.ripe.rpki.validator3.storage.stores.GenericStoreImpl;
import net.ripe.rpki.validator3.storage.stores.RpkiRepositoryStore;
import net.ripe.rpki.validator3.storage.stores.TrustAnchorStore;
import net.ripe.rpki.validator3.util.Rsync;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.validation.constraints.NotNull;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class LmdbRpkiRepostiories extends GenericStoreImpl<RpkiRepository> implements RpkiRepositoryStore {

    private static final String RPKI_REPOSITORIES = "rpki-repositories";
    private static final String BY_URI = "by-uri";
    private static final String BY_TA = "by-ta";

    private final IxMap<RpkiRepository> ixMap;
    private final Sequences sequences;
    private final TrustAnchorStore trustAnchorStore;
    private final ValidationScheduler validationScheduler;

    @Autowired
    public LmdbRpkiRepostiories(Lmdb lmdb, TrustAnchorStore trustAnchorStore, ValidationScheduler validationScheduler) {
        this.trustAnchorStore = trustAnchorStore;
        this.validationScheduler = validationScheduler;

        ixMap = new IxMap<>(
                lmdb.getEnv(),
                RPKI_REPOSITORIES,
                new FSTCoder<>(),
                ImmutableMap.of(
                        BY_URI, r -> Key.keys(Key.of(r.getLocationUri())),
                        BY_TA, r -> r.getTrustAnchors().stream().map(Ref::key).collect(Collectors.toSet())
                )
        );
        sequences = new Sequences(lmdb);
    }


    @Override
    public RpkiRepository register(Tx.Write tx, TrustAnchor trustAnchor, String uri, RpkiRepository.Type type) {
        log.info("Registering repository {} of type {}", uri, type);
        final Optional<RpkiRepository> existing = findByURI(tx, uri);

        final Ref<TrustAnchor> taRef = trustAnchorStore.makeRef(tx, trustAnchor.key());
        final RpkiRepository registered;
        if (existing.isPresent()) {
            registered = existing.get();
            registered.addTrustAnchor(taRef);
        } else {
            registered = new RpkiRepository(taRef, uri, type);
            final Key primaryKey = Key.of(sequences.next(tx, RPKI_REPOSITORIES + ":pk"));
            registered.setId(primaryKey);
        }

        if (type == RpkiRepository.Type.RSYNC && registered.getType() == RpkiRepository.Type.RSYNC_PREFETCH) {
            registered.setType(RpkiRepository.Type.RSYNC);
        }

        if (registered.getType() == RpkiRepository.Type.RSYNC) {
            findRsyncParentRepository(tx, uri).ifPresent(parent -> {
                registered.setParentRepository(Ref.of(tx, ixMap, parent.getId()));
                if (parent.isDownloaded()) {
                    registered.setDownloaded(parent.getLastDownloadedAt());
                }
            });
        }
        ixMap.put(tx, registered.key(), registered);
        return registered;
    }

    private Optional<RpkiRepository> findRsyncParentRepository(Tx.Read tx, @NotNull @ValidLocationURI String uri) {
        return Rsync.generateCandidateParentUris(URI.create(uri)).stream()
                .map(parentURI -> findByURI(tx, parentURI.toASCIIString()))
                .filter(Optional::isPresent)
                .findFirst()
                .flatMap(Function.identity());
    }

    @Override
    public void update(Tx.Write tx, RpkiRepository rpkiRepository) {
        ixMap.put(tx, rpkiRepository.key(), rpkiRepository);
    }

    @Override
    public Optional<RpkiRepository> findByURI(Tx.Read tx, String uri) {
        return ixMap.getByIndex(BY_URI, tx, Key.of(uri)).values().stream().findFirst();
    }

    @Override
    public Optional<RpkiRepository> get(Tx.Read tx, Key id) {
        return ixMap.get(tx, id);
    }

    @Override
    public Stream<RpkiRepository> findAll(Tx.Read tx, RpkiRepository.Status optionalStatus, Key taId,
                                          boolean hideChildrenOfDownloadedParent,
                                          SearchTerm searchTerm, Sorting sorting, Paging paging) {
        return applyPaged(
                applySorting(
                        applyFiltered(tx, optionalStatus, taId, hideChildrenOfDownloadedParent, searchTerm),
                        sorting),
                paging);
    }

    // TODO Optimize it with forEach
    private Stream<RpkiRepository> applyFiltered(Tx.Read tx,
                                                 RpkiRepository.Status optionalStatus,
                                                 Key taId, boolean hideChildrenOfDownloadedParent,
                                                 SearchTerm searchTerm) {
        Stream<RpkiRepository> stream = taId != null ?
                ixMap.getByIndex(BY_TA, tx, taId).values().stream() :
                ixMap.values(tx).stream();

        if (optionalStatus != null) {
            stream = stream.filter(r -> r.getStatus() == optionalStatus);
        }

        if (searchTerm != null) {
            final String stringTerm = searchTerm.asString().toLowerCase();
            stream = stream.filter(r ->
                    r.getLocationUri() != null && r.getLocationUri().toLowerCase().contains(stringTerm) ||
                    r.getStatus() != null && r.getStatus().toString().toLowerCase().contains(stringTerm));
        }

        if (hideChildrenOfDownloadedParent) {
            stream = stream.filter(r -> {
                final Ref<RpkiRepository> parentRef = r.getParentRepository();
                if (parentRef == null) {
                    return true;
                }
                final Optional<RpkiRepository> parent = ixMap.get(tx, parentRef.key());
                return !parent.isPresent() ||
                        parent.get().getStatus() == RpkiRepository.Status.FAILED &&
                                parent.get().getLastDownloadedAt() == null;
            });
        }
        return stream;
    }

    private Stream<RpkiRepository> applySorting(Stream<RpkiRepository> stream, Sorting sorting) {
        if (sorting == null) {
            sorting = Sorting.of(Sorting.By.LOCATION, Sorting.Direction.ASC);
        }
        Comparator<RpkiRepository> comparator;
        switch (sorting.getBy()) {
            case TYPE:
                comparator = Comparator.comparing(RpkiRepository::getType);
                break;
            case STATUS:
                comparator = Comparator.comparing(RpkiRepository::getStatus);
                break;
            case LASTCHECKED:
                comparator = Comparator.comparing(RpkiRepository::getUpdatedAt);
                break;
            case LOCATION:
            default:
                comparator = Comparator.comparing(RpkiRepository::getLocationUri);
                break;
        }
        return stream.sorted(
                sorting.getDirection() == Sorting.Direction.DESC ?
                        comparator :
                        comparator.reversed());
    }

    private Stream<RpkiRepository> applyPaged(Stream<RpkiRepository> stream, Paging paging) {
        if (paging != null) {
            return paging.apply(stream);
        }
        return stream;
    }

    @Override
    public long countAll(Tx.Read tx, RpkiRepository.Status optionalStatus, Key taId,
                         boolean hideChildrenOfDownloadedParent, SearchTerm searchTerm) {
        return applyFiltered(tx, optionalStatus, taId, hideChildrenOfDownloadedParent, searchTerm).count();
    }

    @Override
    public Stream<RpkiRepository> findAll(Tx.Read tx, RpkiRepository.Status optionalStatus, Key taId) {
        final Stream<RpkiRepository> all = findAll(tx, taId);
        return optionalStatus == null ? all : all.filter(r -> r.getStatus() == optionalStatus);
    }

    @Override
    public Stream<RpkiRepository> findAll(Tx.Read tx, Key taId) {
        return ixMap.getByIndex(BY_TA, tx, taId).values().stream();
    }

    @Override
    public Map<RpkiRepository.Status, Long> countByStatus(Tx.Read tx, Key taId, boolean hideChildrenOfDownloadedParent) {
        return findAll(tx, null, taId, hideChildrenOfDownloadedParent, null, null, null)
                .collect(Collectors.groupingBy(RpkiRepository::getStatus, Collectors.counting()));
    }

    @Override
    public Stream<RpkiRepository> findRsyncRepositories(Tx.Read tx) {
        return findRepositoriesByPredicate(tx, r ->
                r.getType() == RpkiRepository.Type.RSYNC ||
                r.getType() == RpkiRepository.Type.RSYNC_PREFETCH);
    }

    @Override
    public Stream<RpkiRepository> findRrdpRepositories(Tx.Read tx) {
        return findRepositoriesByPredicate(tx, r -> r.getType() == RpkiRepository.Type.RRDP);
    }

    private Stream<RpkiRepository> findRepositoriesByPredicate(Tx.Read tx, Predicate<RpkiRepository> p) {
        final List<RpkiRepository> result = new ArrayList<>();
        ixMap.forEach(tx, (k, bb) -> {
            final RpkiRepository r = ixMap.toValue(bb);
            if (p.test(r)) {
                result.add(r);
            }
        });
        return result.stream();
    }

    @Override
    public void removeAllForTrustAnchor(Tx.Write tx, TrustAnchor trustAnchor) {
        ixMap.getByIndex(BY_TA, tx, trustAnchor.key()).forEach((pk, rpkiRepository) -> {
            final Ref<TrustAnchor> taRef = Ref.unsafe(TrustAnchorStore.TRUST_ANCHORS, trustAnchor.getId());
            rpkiRepository.removeTrustAnchor(taRef);
            if (rpkiRepository.getTrustAnchors().isEmpty()) {
                if (rpkiRepository.getType() == RpkiRepository.Type.RRDP) {
                    // TODO Move it outside form here
                    validationScheduler.removeRpkiRepository(rpkiRepository);
                }
                ixMap.delete(tx, pk);
            } else {
                ixMap.put(tx, pk, rpkiRepository);
            }
        });
    }

    @Override
    public void remove(Tx.Write tx, Key key) {
        ixMap.delete(tx, key);
    }

    @Override
    protected IxMap<RpkiRepository> ixMap() {
        return ixMap;
    }
}