/*
 * Copyright (c) 2016, The National Archives <pronom@nationalarchives.gov.uk>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following
 * conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *
 *  * Neither the name of the The National Archives nor the
 *    names of its contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package uk.gov.nationalarchives.droid.internal.api;

import java.nio.file.Files;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.compress.harmony.unpack200.bytecode.forms.ThisFieldRefForm;
import org.apache.commons.lang.StringUtils;

import uk.bl.wa.nanite.droid.InputStreamIdentificationRequest;
import uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.DroidCore;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResultCollection;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResult;
import uk.gov.nationalarchives.droid.core.interfaces.RequestIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.archive.ContainerIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.resource.FileSystemIdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.resource.RequestMetaData;
import uk.gov.nationalarchives.droid.core.signature.droid6.FFSignatureFile;


/**
 * <p>
 * TNA INTERNAL !!! class which encapsulate DROID internal non-friendly api and expose it in simple way.
 * </p>
 * <p>
 * To obtain instance of this class, use factory method {@link #getInstance(Path, Path)} to obtain instance.
 * Obtaining instance is expensive operation and if used multiple time, instance should be cached.
 * Instance should be thread-safe, but we didn't run any internal audit. We suggest creating one instance for every thread.
 * </p>
 * <p>
 * To identify file, use method {@link #submit(Path)}. This method take full path to file which should be identified.
 * It returns identification result which can contain 0..N signatures. Bear in mind that single file can have zero to multiple
 * signature matches!
 * </p>
 * <p>
 * This version of the based class extends it to allow more control over configuration and to allow it to be used with InputStreams etc.
 * </p>
 */
public final class DroidAPIExtended {

    private static final String ZIP_PUID = "x-fmt/263";
    private static final String OLE2_PUID = "fmt/111";

    private static final AtomicLong ID_GENERATOR = new AtomicLong();

    private final DroidCore droidCore;

    private final ContainerIdentifier zipIdentifier;

    private final ContainerIdentifier ole2Identifier;

    private final String containerSignatureVersion;

    private final String binarySignatureVersion;

    private final String droidVersion;
    
    private long maxBytesToScan = -1;
	
	private boolean binarySignaturesOnly;
	
    /**
     * Whether to allow the BinSig matcher to fall back on the file extension
     * when magic matching fails:
     */
    private boolean allowMatchByFileExtension = true;
    
    private FFSignatureFile signatureFile;
	

    private DroidAPIExtended(DroidCore droidCore, FFSignatureFile ffSignatureFile, ContainerIdentifier zipIdentifier, ContainerIdentifier ole2Identifier, String containerSignatureVersion, String binarySignatureVersion, String droidVersion, long maxBytesToScan) {
        this.droidCore = droidCore;
        this.signatureFile = ffSignatureFile;
        this.zipIdentifier = zipIdentifier;
        this.ole2Identifier = ole2Identifier;
        this.containerSignatureVersion = containerSignatureVersion;
        this.binarySignatureVersion = binarySignatureVersion;
        this.droidVersion = droidVersion;
        this.maxBytesToScan = maxBytesToScan;
    }

    /**
     * Return instance, or throw error.
     * @param binarySignature Path to xml file with binary signatures.
     * @param containerSignature Path to xml file with contained signatures.
     * @return Instance of droid with binary and container signature.
     * @throws SignatureParseException On invalid signature file.
     */
    public static DroidAPIExtended getInstance(final Path binarySignature, final Path containerSignature, long maxBytesToScan) throws SignatureParseException {
        BinarySignatureIdentifier droidCore = new BinarySignatureIdentifier();
        droidCore.setSignatureFile(binarySignature.toAbsolutePath().toString());

        droidCore.init();
        if( maxBytesToScan < 0 ) {
        	maxBytesToScan = Long.MAX_VALUE;
        }
        droidCore.setMaxBytesToScan(maxBytesToScan);
        droidCore.getSigFile().prepareForUse();
        String containerVersion = StringUtils.substringAfterLast(containerSignature.getFileName().toString(), "-").split("\\.")[0];
        String droidVersion = ResourceBundle.getBundle("options").getString("version_no");
        ContainerApi containerApi = new ContainerApi(droidCore, containerSignature);
        return new DroidAPIExtended(droidCore, droidCore.getSigFile(), containerApi.zipIdentifier(), containerApi.ole2Identifier(), containerVersion, droidCore.getSigFile().getVersion(), droidVersion, maxBytesToScan);
    }

    /**
     * Submit file for identification. It's important that file has proper file extension. If file
     * can't be identified via binary or container signature, then we use file extension for identification.
     * @param file Full path to file for identification.
     * @return File identification result. File can have multiple matching signatures.
     * @throws IOException If File can't be read or there is IO error.
     */
    public List<ApiResultExtended> submit(final Path file) throws IOException {
        final RequestMetaData metaData = new RequestMetaData(
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                file.toAbsolutePath().toString()
        );

        final RequestIdentifier id = new RequestIdentifier(file.toAbsolutePath().toUri());
        id.setParentId(ID_GENERATOR.getAndIncrement());
        id.setNodeId(ID_GENERATOR.getAndIncrement());

        try (final FileSystemIdentificationRequest request = new FileSystemIdentificationRequest(metaData, id)) {
        	request.open(file);
        	return submit(request);
        }
    }
    
    
    public List<ApiResultExtended> submit(final InputStream input, final String filePath) throws IOException {
        RequestMetaData metaData = new RequestMetaData(
                (long) input.available(), null, filePath);

        URI nameUri = null;
        try {
            if (filePath.startsWith("file:")) {
                nameUri = new URI(filePath);
            } else {
                nameUri = new URI("file", "", "/" + filePath, null);
            }
        } catch (URISyntaxException e) {
            nameUri = URI.create("file://./name-with-no-extension");
        }
        final RequestIdentifier id = new RequestIdentifier(nameUri);
        id.setParentId(ID_GENERATOR.getAndIncrement());
        id.setNodeId(ID_GENERATOR.getAndIncrement());


        try (InputStreamIdentificationRequest request = new InputStreamIdentificationRequest(metaData, id)){
        	request.open(input);
        	return submit(request);
        }
    }
    
    private List<ApiResultExtended> submit(IdentificationRequest request) throws IOException {
        IdentificationResultCollection resultCollection;

        String extension = request.getExtension();

        IdentificationResultCollection binaryResult = droidCore.matchBinarySignatures(request);
        Optional<String> containerPuid = getContainerPuid(binaryResult);

        if (!this.binarySignaturesOnly && containerPuid.isPresent()) {
            resultCollection = handleContainer(binaryResult, request, containerPuid.get());
        } else {
            droidCore.removeLowerPriorityHits(binaryResult);
            droidCore.checkForExtensionsMismatches(binaryResult, request.getExtension());
            if (binaryResult.getResults().isEmpty() && this.allowMatchByFileExtension) {
                resultCollection = identifyByExtension(request);
            } else {
                resultCollection = binaryResult;
            }
        }
        return resultCollection.getResults()
                .stream().map(res -> ApiResultExtended.fromIdentificationResult(extension, res, this.signatureFile))
                .collect(Collectors.toList());
    }

    private IdentificationResultCollection identifyByExtension(final IdentificationRequest identificationRequest) {
        IdentificationResultCollection extensionResult = droidCore.matchExtensions(identificationRequest, false);
        droidCore.removeLowerPriorityHits(extensionResult);
        return extensionResult;
    }

    private Optional<String> getContainerPuid(final IdentificationResultCollection binaryResult) {
        return binaryResult.getResults().stream().filter(x ->
                ZIP_PUID.equals(x.getPuid()) || OLE2_PUID.equals(x.getPuid())
        ).map(IdentificationResult::getPuid).findFirst();
    }

    private IdentificationResultCollection handleContainer(final IdentificationResultCollection binaryResult,
                                                           final IdentificationRequest identificationRequest, final String containerPuid) throws IOException {
        ContainerIdentifier identifier;

        switch (containerPuid) {
            case ZIP_PUID:
                identifier = zipIdentifier;
                break;
            case OLE2_PUID:
                identifier = ole2Identifier;
                break;
            default:
                throw new RuntimeException("Unknown container PUID : " + containerPuid);
        }

        IdentificationResultCollection containerResults = identifier.submit(identificationRequest);
        droidCore.removeLowerPriorityHits(containerResults);
        droidCore.checkForExtensionsMismatches(containerResults, identificationRequest.getExtension());
        containerResults.setFileLength(identificationRequest.size());
        containerResults.setRequestMetaData(identificationRequest.getRequestMetaData());

        return containerResults.getResults().isEmpty() ? binaryResult : containerResults;
    }

    public String getContainerSignatureVersion() {
        return containerSignatureVersion;
    }

    public String getBinarySignatureVersion() {
        return binarySignatureVersion;
    }

    public String getDroidVersion() {
        return droidVersion;
    }
    
    /**
     * @return the binarySignaturesOnly
     */
    public boolean isBinarySignaturesOnly() {
        return binarySignaturesOnly;
    }

    /**
     * @param binarySignaturesOnly
     *            the binarySignaturesOnly to set
     */
    public void setBinarySignaturesOnly(boolean binarySignaturesOnly) {
        this.binarySignaturesOnly = binarySignaturesOnly;
    }
    
    public boolean isAllowMatchByFileExtension() {
        return allowMatchByFileExtension;
    }

    public void setAllowMatchByFileExtension(
            boolean allowMatchByFileExtension) {
        this.allowMatchByFileExtension = allowMatchByFileExtension;
    }

    /**
     * @return the maxBytesToScan
     */
    public long getMaxBytesToScan() {
        return this.maxBytesToScan;
    }

    /**
     * @param maxBytesToScan
     *            the maxBytesToScan to set
     */
    public void setMaxBytesToScan(long maxBytesToScan) {
        this.droidCore.setMaxBytesToScan(maxBytesToScan);
    	this.maxBytesToScan = maxBytesToScan;
    }
    
}
