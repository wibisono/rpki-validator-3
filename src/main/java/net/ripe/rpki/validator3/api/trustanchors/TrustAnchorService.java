package net.ripe.rpki.validator3.api.trustanchors;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.validator3.domain.RpkiRepositories;
import net.ripe.rpki.validator3.domain.RpkiRepository;
import net.ripe.rpki.validator3.domain.TrustAnchor;
import net.ripe.rpki.validator3.domain.TrustAnchors;
import net.ripe.rpki.validator3.domain.ValidationRuns;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.transaction.Transactional;
import javax.validation.Valid;

@Component
@Transactional
@Validated
@Slf4j
public class TrustAnchorService {
    private final TrustAnchors trustAnchorRepository;
    private final RpkiRepositories rpkiRepositories;
    private final ValidationRuns validationRunRepository;

    @Autowired
    public TrustAnchorService(TrustAnchors trustAnchorRepository, RpkiRepositories rpkiRepositories, ValidationRuns validationRunRepository) {
        this.trustAnchorRepository = trustAnchorRepository;
        this.rpkiRepositories = rpkiRepositories;
        this.validationRunRepository = validationRunRepository;
    }

    public long execute(@Valid AddTrustAnchor command) {
        TrustAnchor trustAnchor = new TrustAnchor();
        trustAnchor.setName(command.getName());
        trustAnchor.setLocations(command.getLocations());
        trustAnchor.setSubjectPublicKeyInfo(command.getSubjectPublicKeyInfo());

        if (command.getRsyncPrefetchUri() != null) {
            rpkiRepositories.register(trustAnchor, command.getRsyncPrefetchUri(), RpkiRepository.Type.RSYNC_PREFETCH);
        }

        trustAnchorRepository.add(trustAnchor);

        return trustAnchor.getId();
    }

    public void remove(long trustAnchorId) {
        TrustAnchor trustAnchor = trustAnchorRepository.get(trustAnchorId);
        validationRunRepository.removeAllForTrustAnchor(trustAnchor);
        rpkiRepositories.removeAllForTrustAnchor(trustAnchor);
        trustAnchorRepository.remove(trustAnchor);
    }
}
