package net.ripe.rpki.validator3.domain.validation;

import com.google.common.io.Files;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject;
import net.ripe.rpki.commons.crypto.util.CertificateRepositoryObjectFactory;
import net.ripe.rpki.commons.crypto.x509cert.X509CertificateUtil;
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificate;
import net.ripe.rpki.commons.rsync.CommandExecutionException;
import net.ripe.rpki.commons.rsync.Rsync;
import net.ripe.rpki.commons.validation.ValidationResult;
import net.ripe.rpki.validator3.domain.*;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;

@Service
@Slf4j
public class ValidationService {

    private static final int DEFAULT_RSYNC_PORT = 873;

    private final TrustAnchorRepository trustAnchorRepository;
    private final RpkiObjectRepository rpkiObjectRepository;
    private final ValidationRunRepository validationRunRepository;
    private final File localRsyncStorageDirectory;

    public ValidationService(
        TrustAnchorRepository trustAnchorRepository,
        RpkiObjectRepository rpkiObjectRepository,
        ValidationRunRepository validationRunRepository,
        @Value("${rpki.validator.local.rsync.storage.directory}") File localRsyncStorageDirectory
    ) {
        this.trustAnchorRepository = trustAnchorRepository;
        this.rpkiObjectRepository = rpkiObjectRepository;
        this.validationRunRepository = validationRunRepository;
        this.localRsyncStorageDirectory = localRsyncStorageDirectory;
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void validate(long trustAnchorId) {
        TrustAnchor trustAnchor = trustAnchorRepository.get(trustAnchorId);

        log.debug("trust anchor {} located at {} with subject public key info {}", trustAnchor.getName(), trustAnchor.getLocations(), trustAnchor.getSubjectPublicKeyInfo());

        ValidationRun validationRun = new ValidationRun(trustAnchor);
        validationRunRepository.add(validationRun);

        try {
            URI trustAnchorCertificateURI = URI.create(validationRun.getTrustAnchorCertificateURI()).normalize();
            ValidationResult validationResult = ValidationResult.withLocation(trustAnchorCertificateURI);

            File targetFile = fetchTrustAnchorCertificate(trustAnchor, trustAnchorCertificateURI, validationResult);
            if (targetFile != null) {
                long trustAnchorCertificateSize = targetFile.length();

                if (trustAnchorCertificateSize == 0L) {
                    validationResult.error("repository.object.empty");
                } else if (trustAnchorCertificateSize > RpkiObject.MAX_SIZE) {
                    validationResult.error("repository.object.too.large", String.valueOf(trustAnchorCertificateSize), String.valueOf(RpkiObject.MAX_SIZE));
                } else {
                    X509ResourceCertificate certificate = parseCertificate(trustAnchor, targetFile, validationResult);

                    if (!validationResult.hasFailureForCurrentLocation()) {
                        // check valid self-signed signature (trust anchor rules)
                        // check subject public key hash against TAL
                        // validity time?
                        if (trustAnchor.getCertificate() == null || trustAnchor.getCertificate().getSerialNumber().compareTo(certificate.getSerialNumber()) <= 0) {
                            trustAnchor.setCertificate(certificate);
                        } else {
                            validationResult.warn("repository.object.is.older.than.previous.object", trustAnchorCertificateURI.toASCIIString());
                        }
                    }
                }
            }

            for (net.ripe.rpki.commons.validation.ValidationCheck check : validationResult.getFailuresForCurrentLocation()) {
                ValidationCheck validationCheck = new ValidationCheck(validationRun, null, trustAnchorCertificateURI.toASCIIString(), check);
                validationRun.addCheck(validationCheck);
            }

            if (validationResult.hasFailures()) {
                validationRun.failed();
            } else {
                validationRun.succeeded();
            }
        } catch (CommandExecutionException | IOException e) {
            log.error("validation run for trust anchor {} failed", trustAnchor, e);
            validationRun.addCheck(new ValidationCheck(validationRun, validationRun.getTrustAnchorCertificateURI(), ValidationCheck.Status.ERROR, "unhandled.exception", e.toString()));
            validationRun.failed();
        }

    }

    private X509ResourceCertificate parseCertificate(TrustAnchor trustAnchor, File certificateFile, ValidationResult validationResult) throws IOException {
        CertificateRepositoryObject trustAnchorCertificate = CertificateRepositoryObjectFactory.createCertificateRepositoryObject(Files.toByteArray(certificateFile), validationResult);
        if (!(trustAnchorCertificate instanceof X509ResourceCertificate)) {
            validationResult.error("repository.object.is.not.a.trust.anchor.certificate");
            return null;
        }

        X509ResourceCertificate certificate = (X509ResourceCertificate) trustAnchorCertificate;

        String encodedSubjectPublicKeyInfo = X509CertificateUtil.getEncodedSubjectPublicKeyInfo(certificate.getCertificate());
        validationResult.rejectIfFalse(encodedSubjectPublicKeyInfo.equals(trustAnchor.getSubjectPublicKeyInfo()),"trust.anchor.subject.key.matches.locator");

        boolean signatureValid;
        try {
            certificate.getCertificate().verify(certificate.getPublicKey());
            signatureValid = true;
        } catch (GeneralSecurityException e) {
            signatureValid = false;
        }

        validationResult.rejectIfFalse(signatureValid, "trust.anchor.signature.valid");

        return certificate;
    }

    private File fetchTrustAnchorCertificate(TrustAnchor trustAnchor, URI trustAnchorCertificateURI, ValidationResult validationResult) throws IOException {
        File trustAnchorDirectory = new File(localRsyncStorageDirectory, String.valueOf(trustAnchor.getId()));
        String trustAnchorHost = trustAnchorCertificateURI.getHost() + "/" + (trustAnchorCertificateURI.getPort() < 0 ? DEFAULT_RSYNC_PORT : trustAnchorCertificateURI.getPort());
        File targetFile = new File(
            new File(trustAnchorDirectory.getCanonicalFile(), trustAnchorHost),
            trustAnchorCertificateURI.getRawPath()
        ).getCanonicalFile();

        if (targetFile.getParentFile().mkdirs()) {
            log.info("created local rsync storage directory {} for trust anchor {}", targetFile.getParentFile(), trustAnchorCertificateURI);
        }

        Rsync rsync = new Rsync(trustAnchorCertificateURI.toASCIIString(), targetFile.getPath());
        rsync.addOptions("--update", "--times", "--copy-links");
        int exitStatus = rsync.execute();
        if (exitStatus != 0) {
            validationResult.error("rsync.error", String.valueOf(exitStatus), ArrayUtils.toString(rsync.getErrorLines()));
            return null;
        } else {
            log.info("Downloaded certificate {} to {}", trustAnchorCertificateURI, targetFile);
            return targetFile;
        }
    }
}