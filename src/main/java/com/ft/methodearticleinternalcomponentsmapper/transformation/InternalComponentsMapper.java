package com.ft.methodearticleinternalcomponentsmapper.transformation;

import com.ft.bodyprocessing.BodyProcessor;
import com.ft.methodearticleinternalcomponentsmapper.exception.MethodeArticleMarkedDeletedException;
import com.ft.methodearticleinternalcomponentsmapper.exception.MethodeArticleNotEligibleForPublishException;
import com.ft.methodearticleinternalcomponentsmapper.exception.TransformationException;
import com.ft.methodearticleinternalcomponentsmapper.exception.UntransformableMethodeContentException;
import com.ft.methodearticleinternalcomponentsmapper.model.*;
import com.ft.methodearticleinternalcomponentsmapper.validation.MethodeArticleValidator;
import com.ft.methodearticleinternalcomponentsmapper.validation.PublishingStatus;
import com.ft.uuidutils.DeriveUUID;
import com.google.common.base.Strings;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.ft.uuidutils.DeriveUUID.Salts.IMAGE_SET;

public class InternalComponentsMapper {

    private static Logger LOGGER = LoggerFactory.getLogger(InternalComponentsMapper.class);

    enum TransformationMode {
        PUBLISH,
        PREVIEW
    }

    interface Type {
        String CONTENT_PACKAGE = "ContentPackage";
    }

    private MethodeArticleValidator methodeArticleValidator;
    private BodyProcessor htmlFieldProcessor;
    private FieldTransformer bodyTransformer;

    private static final String NO_PICTURE_FLAG = "No picture";
    private static final String DEFAULT_IMAGE_ATTRIBUTE_DATA_EMBEDDED = "data-embedded";
    private static final String IMAGE_SET_TYPE = "http://www.ft.com/ontology/content/ImageSet";
    private static final String BODY_TAG_XPATH = "/doc/story/text/body";

    private static final String START_BODY = "<body";
    private static final String END_BODY = "</body>";
    private static final String EMPTY_VALIDATED_BODY = "<body></body>";

    public InternalComponentsMapper(final FieldTransformer bodyTransformer,
                                    final MethodeArticleValidator methodeArticleValidator,
                                    final BodyProcessor htmlFieldProcessor) {
        this.methodeArticleValidator = methodeArticleValidator;
        this.htmlFieldProcessor = htmlFieldProcessor;
        this.bodyTransformer = bodyTransformer;
    }

    public InternalComponents map(EomFile eomFile, String transactionId, Date lastModified, boolean preview) {
        PublishingStatus status = methodeArticleValidator.getPublishingStatus(eomFile, transactionId, preview);
        UUID uuid = UUID.fromString(eomFile.getUuid());
        switch (status) {
            case INELIGIBLE:
                throw new MethodeArticleNotEligibleForPublishException(uuid);
            case DELETED:
                throw new MethodeArticleMarkedDeletedException(uuid);
        }

        try {
            final XPath xpath = XPathFactory.newInstance().newXPath();
            final Document eomFileDocument = getEomFileDocument(eomFile);

            final Design design = extractDesign(xpath, eomFileDocument);
            final TableOfContents tableOfContents = extractTableOfContents(xpath, eomFileDocument);
            final List<Image> leadImages = extractImages(xpath, eomFileDocument, "/doc/lead/lead-image-set/lead-image-");
            final Topper topper = extractTopper(xpath, eomFileDocument);
            final String unpublishedContentDescription = extractUnpublishedContentDescription(xpath, eomFileDocument);

            String sourceBodyXML = retrieveField(xpath, BODY_TAG_XPATH, eomFileDocument);
            final String transformedBodyXML = transformBody(xpath, sourceBodyXML, eomFile.getAttributes(), eomFile.getValue(), transactionId, uuid, preview);

            return InternalComponents.builder()
                    .withUuid(uuid)
                    .withPublishReference(transactionId)
                    .withLastModified(lastModified)
                    .withDesign(design)
                    .withTableOfContents(tableOfContents)
                    .withTopper(topper)
                    .withLeadImages(leadImages)
                    .withUnpublishedContentDescription(unpublishedContentDescription)
                    .withXMLBody(transformedBodyXML)
                    .build();
        } catch (ParserConfigurationException | SAXException | XPathExpressionException | TransformerException | IOException e) {
            throw new TransformationException(e);
        }
    }

    private String retrieveField(XPath xpath, String expression, Document eomFileDocument) throws TransformerException, XPathExpressionException {
        final Node node = (Node) xpath.evaluate(expression, eomFileDocument, XPathConstants.NODE);
        return getNodeAsString(node);
    }

    private String getNodeAsString(Node node) throws TransformerException {
        return convertNodeToStringReturningEmptyIfNull(node);
    }

    private String convertNodeToStringReturningEmptyIfNull(Node node) throws TransformerException {
        StringWriter writer = new StringWriter();
        final TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        return writer.toString();
    }

    private String transformBody(XPath xpath, String sourceBodyXML, String attributes, byte[] value, String transactionId, UUID uuid, boolean preview) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException, TransformerException {
        TransformationMode mode = preview ? TransformationMode.PREVIEW : TransformationMode.PUBLISH;
        Document attributesDocument = getDocumentBuilder().parse(new InputSource(new StringReader(attributes)));
        Document valueDocument = getDocumentBuilder().parse(new ByteArrayInputStream(value));
        final String type = determineType(xpath, attributesDocument);

        final String transformedBody = transformField(sourceBodyXML, bodyTransformer, transactionId);
        final String validatedBody = validateBody(mode, type, transformedBody, uuid);

        final String mainImage = generateMainImageUuid(xpath, valueDocument);
        final String postProcessedBody = putMainImageReferenceInBodyXml(xpath, attributesDocument, mainImage, validatedBody);

        return postProcessedBody;
    }

    private String determineType(final XPath xpath,
                                 final Document attributesDocument) throws XPathExpressionException {
        final String isContentPackage = xpath.evaluate("/ObjectMetadata/OutputChannels/DIFTcom/isContentPackage", attributesDocument);
        if (Boolean.TRUE.toString().equalsIgnoreCase(isContentPackage)) {
            return Type.CONTENT_PACKAGE;
        }

        return null;
    }

    private String transformField(String originalFieldAsString, FieldTransformer transformer, String transactionId) {
        String transformedField = "";
        if (!Strings.isNullOrEmpty(originalFieldAsString)) {
            transformedField = transformer.transform(originalFieldAsString, transactionId);
        }
        return transformedField;
    }

    private String validateBody(final TransformationMode mode,
                                final String type,
                                final String transformedBody,
                                final UUID uuid) {
        if (!Strings.isNullOrEmpty(transformedBody) && !Strings.isNullOrEmpty(unwrapBody(transformedBody))) {
            return transformedBody;
        }

        if (TransformationMode.PREVIEW.equals(mode)) {
            return EMPTY_VALIDATED_BODY;
        }

        if (Type.CONTENT_PACKAGE.equals(type)) {
            return EMPTY_VALIDATED_BODY;
        }

        throw new UntransformableMethodeContentException(uuid.toString(), "Not a valid Methode article for publication - transformed article body is blank");
    }

    private String unwrapBody(String wrappedBody) {
        if (!(wrappedBody.startsWith(START_BODY) && wrappedBody.endsWith(END_BODY))) {
            throw new IllegalArgumentException("can't unwrap a string that is not a wrapped body");
        }

        int index = wrappedBody.indexOf('>', START_BODY.length()) + 1;
        return wrappedBody.substring(index, wrappedBody.length() - END_BODY.length()).trim();
    }

    private String generateMainImageUuid(XPath xpath, Document eomFileDocument) throws XPathExpressionException {
        final String imageUuid = StringUtils.substringAfter(xpath.evaluate("/doc/lead/lead-images/web-master/@fileref", eomFileDocument), "uuid=");
        if (!Strings.isNullOrEmpty(imageUuid)) {
            return DeriveUUID.with(IMAGE_SET).from(UUID.fromString(imageUuid)).toString();
        }
        return null;
    }

    private String putMainImageReferenceInBodyXml(XPath xpath, Document attributesDocument, String mainImage, String body) throws XPathExpressionException,
            TransformerException, ParserConfigurationException, SAXException, IOException {

        if (mainImage != null) {

            InputSource inputSource = new InputSource();
            inputSource.setCharacterStream(new StringReader(body));

            Element bodyNode = getDocumentBuilder()
                    .parse(inputSource)
                    .getDocumentElement();
            final String flag = xpath.evaluate("/ObjectMetadata/OutputChannels/DIFTcom/DIFTcomArticleImage", attributesDocument);
            if (!NO_PICTURE_FLAG.equalsIgnoreCase(flag)) {
                return putMainImageReferenceInBodyNode(bodyNode, mainImage);
            }
        }
        return body;
    }

    private DocumentBuilder getDocumentBuilder() throws ParserConfigurationException {
        final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        return documentBuilderFactory.newDocumentBuilder();
    }

    private String putMainImageReferenceInBodyNode(Node bodyNode, String mainImage) throws TransformerException {
        Element newElement = bodyNode.getOwnerDocument().createElement("content");
        newElement.setAttribute("id", mainImage);
        newElement.setAttribute("type", IMAGE_SET_TYPE);
        newElement.setAttribute(DEFAULT_IMAGE_ATTRIBUTE_DATA_EMBEDDED, "true");
        bodyNode.insertBefore(newElement, bodyNode.getFirstChild());
        return getNodeAsHTML5String(bodyNode);
    }

    private Design extractDesign(final XPath xPath, final Document eomFileDoc) throws XPathExpressionException {
        final String theme = Strings.nullToEmpty(xPath.evaluate("/doc/lead/lead-components/content-package/@design-theme", eomFileDoc)).trim();

        if (Strings.isNullOrEmpty(theme)) {
            return null;
        }

        return new Design(theme);
    }

    private TableOfContents extractTableOfContents(final XPath xPath, final Document eomFileDoc) throws XPathExpressionException {
        final String sequence = Strings.nullToEmpty(xPath.evaluate("/doc/lead/lead-components/content-package/@sequence", eomFileDoc)).trim();
        final String labelType = Strings.nullToEmpty(xPath.evaluate("/doc/lead/lead-components/content-package/@label", eomFileDoc)).trim();

        if (Strings.isNullOrEmpty(sequence) && Strings.isNullOrEmpty(labelType)) {
            return null;
        }

        return new TableOfContents(sequence, labelType);
    }

    private Topper extractTopper(final XPath xpath, final Document eomFileDoc) throws XPathExpressionException {
        final String topperBasePath = "/doc/lead/lead-components/topper";

        final String layout = Strings.nullToEmpty(xpath.evaluate(topperBasePath + "/@layout", eomFileDoc)).trim();

        //a topper is valid only if the theme attribute is present. Since layout is the new value for theme, we need to check both
        if (Strings.isNullOrEmpty(layout)) {
            return null;
        }

        final String headline = Strings.nullToEmpty(xpath.evaluate(topperBasePath + "/topper-headline", eomFileDoc)).trim();
        final String standfirst = Strings.nullToEmpty(xpath.evaluate(topperBasePath + "/topper-standfirst", eomFileDoc)).trim();

        final String backgroundColour = Strings.nullToEmpty(xpath.evaluate(topperBasePath + "/@background-colour", eomFileDoc)).trim();

        return new Topper(
                headline,
                standfirst,
                backgroundColour,
                layout);
    }

    private String extractUnpublishedContentDescription(final XPath xpath, final Document eomFileDoc) throws XPathExpressionException, TransformerException {
        final Node contentPackageNextNode = (Node) xpath.evaluate("/doc/lead/lead-components/content-package/content-package-next", eomFileDoc, XPathConstants.NODE);
        if (contentPackageNextNode == null) {
            return null;
        }

        final String contentPackageNext = getNodeAsHTML5String(contentPackageNextNode.getFirstChild());
        if (Strings.isNullOrEmpty(contentPackageNext)) {
            return null;
        }

        final String unpublishedContentDescription = contentPackageNext.trim();
        return Strings.isNullOrEmpty(unpublishedContentDescription) ? null : unpublishedContentDescription;
    }

    private List<Image> extractImages(XPath xpath, Document doc, String basePath) throws XPathExpressionException {
        String[] labels = new String[]{"square", "standard", "wide"};
        List<Image> images = new ArrayList<>();

        for (String label : labels) {
            String id = getImageId(xpath, doc, basePath + label);
            if (Strings.isNullOrEmpty(id)) {
                continue;
            }
            images.add(new Image(id, label));
        }

        return images;
    }

    private String getImageId(XPath xpath, Document doc, String topperImgBasePath) throws XPathExpressionException {
        String topperImageId = null;
        String imageFileRef = Strings.nullToEmpty(xpath.evaluate(topperImgBasePath + "/@fileref", doc)).trim();
        if (imageFileRef.contains("uuid=")) {
            topperImageId = imageFileRef.substring(imageFileRef.lastIndexOf("uuid=") + "uuid=".length());
        }
        return topperImageId;
    }

    private String getNodeAsHTML5String(final Node node) throws TransformerException {
        String nodeAsString = convertNodeToString(node);
        return htmlFieldProcessor.process(nodeAsString, null);
    }

    private String convertNodeToString(final Node node) throws TransformerException {
        final StringWriter writer = new StringWriter();
        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(new DOMSource(node), new StreamResult(writer));
        return writer.toString();
    }

    private Document getEomFileDocument(EomFile eomFile) throws ParserConfigurationException, SAXException, IOException {
        final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        final DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        return documentBuilder.parse(new ByteArrayInputStream(eomFile.getValue()));
    }
}
