package com.ft.methodearticleinternalcomponentsmapper.transformation;

import com.ft.methodearticleinternalcomponentsmapper.exception.MethodeArticleHasNoInternalComponentsException;
import com.ft.methodearticleinternalcomponentsmapper.exception.MethodeArticleMarkedDeletedException;
import com.ft.methodearticleinternalcomponentsmapper.exception.MethodeArticleNotEligibleForPublishException;
import com.ft.methodearticleinternalcomponentsmapper.model.Design;
import com.ft.methodearticleinternalcomponentsmapper.model.EomFile;
import com.ft.methodearticleinternalcomponentsmapper.model.Image;
import com.ft.methodearticleinternalcomponentsmapper.model.InternalComponents;
import com.ft.methodearticleinternalcomponentsmapper.model.TableOfContents;
import com.ft.methodearticleinternalcomponentsmapper.model.Topper;
import com.ft.methodearticleinternalcomponentsmapper.validation.MethodeArticleValidator;
import com.ft.methodearticleinternalcomponentsmapper.validation.PublishingStatus;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class InternalComponentsMapperTest {

    private static final String ARTICLE_UUID = UUID.randomUUID().toString();
    private static final String TX_ID = "tid_test";
    private static final Date LAST_MODIFIED = new Date();

    private static final String ARTICLE_WITH_ALL_COMPONENTS = readFile("article/article_with_all_components.xml.mustache");
    private static final String ARTICLE_WITH_TOPPER = readFile("article/article_with_topper.xml.mustache");
    private static final String ARTICLE_WITH_NO_TOPPER = readFile("article/article_with_no_topper.xml");
    private static final String ARTICLE_WITH_EMPTY_TOPPER = readFile("article/article_with_empty_topper.xml");

    @Mock
    EomFile eomFile;

    @Mock
    private MethodeArticleValidator methodeArticleValidator;

    @InjectMocks
    private InternalComponentsMapper internalComponentsMapper;

    @Before
    public void setUp() {
        when(eomFile.getUuid()).thenReturn(ARTICLE_UUID);
    }

    @Test
    public void thatValidArticleWithTopperIsMappedCorrectly() throws Exception {
        String backgroundColour = "fooBackground";
        String layout = "barColor";
        String headline = "foobar headline";
        String standfirst = "foobar standfirst";

        eomFile = new EomFile.Builder()
                .withUuid(ARTICLE_UUID)
                .withType("EOM::CompoundStory")
                .withValue(buildTopperOnlyEomFileValue(backgroundColour, layout, headline, standfirst))
                .build();

        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.VALID);

        InternalComponents actual = internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);

        assertThat(actual.getUuid(), equalTo(ARTICLE_UUID));
        assertThat(actual.getLastModified(), equalTo(LAST_MODIFIED));
        assertThat(actual.getPublishReference(), equalTo(TX_ID));

        assertThat(actual.getTopper().getBackgroundColour(), equalTo(backgroundColour));
        assertThat(actual.getTopper().getLayout(), equalTo(layout));
        assertThat(actual.getTopper().getStandfirst(), equalTo(standfirst));
        assertThat(actual.getTopper().getHeadline(), equalTo(headline));
    }

    @Test
    public void thatValidArticleWithTopperButEmptyStandfirstAndHeadlineIsMappedCorrectly() throws Exception {
        String backgroundColour = "fooBackground";
        String layout = "barColor";

        eomFile = new EomFile.Builder()
                .withUuid(ARTICLE_UUID)
                .withType("EOM::CompoundStory")
                .withValue(buildTopperOnlyEomFileValue(backgroundColour, layout, "", ""))
                .build();

        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.VALID);

        InternalComponents actual = internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);

        assertThat(actual.getUuid(), equalTo(ARTICLE_UUID));
        assertThat(actual.getLastModified(), equalTo(LAST_MODIFIED));
        assertThat(actual.getPublishReference(), equalTo(TX_ID));

        assertThat(actual.getTopper().getBackgroundColour(), equalTo(backgroundColour));
        assertThat(actual.getTopper().getLayout(), equalTo(layout));
        assertThat(actual.getTopper().getStandfirst(), equalTo(""));
        assertThat(actual.getTopper().getHeadline(), equalTo(""));
    }

    @Test(expected = MethodeArticleHasNoInternalComponentsException.class)
    public void thatValidArticleWithoutInternalComponentsThrowsException() throws Exception {
        eomFile = new EomFile.Builder()
                .withUuid(ARTICLE_UUID)
                .withType("EOM::CompoundStory")
                .withValue(ARTICLE_WITH_NO_TOPPER.getBytes())
                .build();

        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.VALID);

        internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);
    }

    @Test(expected = MethodeArticleHasNoInternalComponentsException.class)
    public void thatValidArticleWithEmptyInternalComponentsThrowsException() throws Exception {
        eomFile = new EomFile.Builder()
                .withUuid(ARTICLE_UUID)
                .withType("EOM::CompoundStory")
                .withValue(ARTICLE_WITH_EMPTY_TOPPER.getBytes())
                .build();

        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.VALID);

        internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);
    }

    @Test(expected = MethodeArticleMarkedDeletedException.class)
    public void thatArticleMarkedAsDeletedThrowsException() throws Exception {
        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.DELETED);

        internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);
    }

    @Test(expected = MethodeArticleNotEligibleForPublishException.class)
    public void thatArticleIneligibleForPublishThrowsException() throws Exception {
        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.INELIGIBLE);

        internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);
    }

    @Test
    public void testValidArticleWithAllComponentsIsMappedCorrectly() {
        final String squareImg = UUID.randomUUID().toString();
        final String standardImg = UUID.randomUUID().toString();
        final String wideImg = UUID.randomUUID().toString();
        final String designTheme = "extra";
        final String sequence = "exact-order";
        final String labelType = "part-number";
        final String backgroundColour = "auto";
        final String layout = "split-text-left";
        final String headline = "Topper headline";
        final String standfirst = "Topper standfirst";

        eomFile = new EomFile.Builder()
                .withUuid(ARTICLE_UUID)
                .withType("EOM::CompoundStory")
                .withValue(buildEomFileValue(squareImg, standardImg, wideImg, designTheme, sequence,
                        labelType, backgroundColour, layout, headline, standfirst))
                .build();

        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.VALID);

        final InternalComponents actual = internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);

        assertThat(actual, is(notNullValue()));
        assertThat(actual.getUuid(), is(ARTICLE_UUID));
        assertThat(actual.getLastModified(), is(LAST_MODIFIED));
        assertThat(actual.getPublishReference(), is(TX_ID));

        final Design design = actual.getDesign();
        assertThat(design, is(notNullValue()));
        assertThat(design.getTheme(), is(designTheme));

        final TableOfContents tableOfContents = actual.getTableOfContents();
        assertThat(tableOfContents, is(notNullValue()));
        assertThat(tableOfContents.getSequence(), is(sequence));
        assertThat(tableOfContents.getLabelType(), is(labelType));

        final List<Image> leadImages = actual.getLeadImages();
        assertThat(leadImages, is(notNullValue()));
        assertThat(leadImages.size(), is(3));
        assertThat(leadImages.get(0).getId(), is(squareImg));
        assertThat(leadImages.get(1).getId(), is(standardImg));
        assertThat(leadImages.get(2).getId(), is(wideImg));

        final Topper topper = actual.getTopper();
        assertThat(topper, is(notNullValue()));
        assertThat(topper.getLayout(), is(layout));
        assertThat(topper.getBackgroundColour(), is(backgroundColour));
        assertThat(topper.getHeadline(), is(headline));
        assertThat(topper.getStandfirst(), is(standfirst));
    }

    @Test
    public void testValidArticleWithMissingTopperLayoutWillHaveNoTopper() {
        final String squareImg = UUID.randomUUID().toString();
        final String standardImg = UUID.randomUUID().toString();
        final String wideImg = UUID.randomUUID().toString();
        final String designTheme = "extra";
        final String sequence = "exact-order";
        final String labelType = "part-number";

        eomFile = new EomFile.Builder()
                .withUuid(ARTICLE_UUID)
                .withType("EOM::CompoundStory")
                .withValue(buildEomFileValue(squareImg, standardImg, wideImg, designTheme, sequence,
                        labelType, "auto", "", "Topper Headline", "Topper standfirst"))
                .build();

        when(methodeArticleValidator.getPublishingStatus(eq(eomFile), eq(TX_ID), anyBoolean()))
                .thenReturn(PublishingStatus.VALID);

        final InternalComponents actual = internalComponentsMapper.map(eomFile, TX_ID, LAST_MODIFIED, false);

        assertThat(actual, is(notNullValue()));
        assertThat(actual.getUuid(), is(ARTICLE_UUID));
        assertThat(actual.getLastModified(), is(LAST_MODIFIED));
        assertThat(actual.getPublishReference(), is(TX_ID));

        final Design design = actual.getDesign();
        assertThat(design, is(notNullValue()));
        assertThat(design.getTheme(), is(designTheme));

        final TableOfContents tableOfContents = actual.getTableOfContents();
        assertThat(tableOfContents, is(notNullValue()));
        assertThat(tableOfContents.getSequence(), is(sequence));
        assertThat(tableOfContents.getLabelType(), is(labelType));

        final List<Image> leadImages = actual.getLeadImages();
        assertThat(leadImages, is(notNullValue()));
        assertThat(leadImages.size(), is(3));
        assertThat(leadImages.get(0).getId(), is(squareImg));
        assertThat(leadImages.get(1).getId(), is(standardImg));
        assertThat(leadImages.get(2).getId(), is(wideImg));

        assertThat(actual.getTopper(), is(nullValue()));
    }

    private byte[] buildTopperOnlyEomFileValue(
            String backgroundColour,
            String layout,
            String headline,
            String standfirst) {

        Template mustache = Mustache.compiler().escapeHTML(false).compile(ARTICLE_WITH_TOPPER);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("backgroundColour", backgroundColour);
        attributes.put("layout", layout);
        attributes.put("headline", headline);
        attributes.put("standfirst", standfirst);

        return mustache.execute(attributes).getBytes(UTF_8);
    }

    private byte[] buildEomFileValue(
            String squareImg,
            String standardImg,
            String wideImg,
            String designTheme,
            String sequence,
            String labelType,
            String backgroundColour,
            String layout,
            String headline,
            String standfirst) {
        Template mustache = Mustache.compiler().escapeHTML(false).compile(ARTICLE_WITH_ALL_COMPONENTS);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("squareImageUUID", squareImg);
        attributes.put("standardImageUUID", standardImg);
        attributes.put("wideImageUUID", wideImg);
        attributes.put("backgroundColour", backgroundColour);
        attributes.put("designTheme", designTheme);
        attributes.put("sequence", sequence);
        attributes.put("labelType", labelType);
        attributes.put("backgroundColour", backgroundColour);
        attributes.put("layout", layout);
        attributes.put("headline", headline);
        attributes.put("standfirst", standfirst);

        return mustache.execute(attributes).getBytes(UTF_8);
    }

    private static String readFile(final String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(
                    InternalComponentsMapperTest.class
                            .getClassLoader()
                            .getResource(path)
                            .toURI())),
                    "UTF-8"
            );
        } catch (IOException | URISyntaxException ex) {
            throw new RuntimeException(ex);
        }
    }
}