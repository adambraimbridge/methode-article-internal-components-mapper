package com.ft.methodearticleinternalcomponentsmapper;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.ft.api.jaxrs.errors.RuntimeExceptionMapper;
import com.ft.api.util.buildinfo.BuildInfoResource;
import com.ft.api.util.transactionid.TransactionIdFilter;
import com.ft.bodyprocessing.html.Html5SelfClosingTagBodyProcessor;
import com.ft.bodyprocessing.richcontent.VideoMatcher;
import com.ft.jerseyhttpwrapper.ResilientClient;
import com.ft.jerseyhttpwrapper.ResilientClientBuilder;
import com.ft.jerseyhttpwrapper.config.EndpointConfiguration;
import com.ft.jerseyhttpwrapper.continuation.ExponentialBackoffContinuationPolicy;
import com.ft.message.consumer.MessageListener;
import com.ft.message.consumer.MessageQueueConsumerInitializer;
import com.ft.messagequeueproducer.MessageProducer;
import com.ft.messagequeueproducer.QueueProxyProducer;
import com.ft.methodearticleinternalcomponentsmapper.configuration.ConcordanceApiConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.configuration.ConnectionConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.configuration.ConsumerConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.configuration.DocumentStoreApiConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.configuration.MethodeArticleInternalComponentsMapperConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.configuration.MethodeMapperConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.configuration.ProducerConfiguration;
import com.ft.methodearticleinternalcomponentsmapper.health.CanConnectToMessageQueueProducerProxyHealthcheck;
import com.ft.methodearticleinternalcomponentsmapper.health.RemoteServiceHealthCheck;
import com.ft.methodearticleinternalcomponentsmapper.messaging.MessageBuilder;
import com.ft.methodearticleinternalcomponentsmapper.messaging.MessageProducingInternalComponentsMapper;
import com.ft.methodearticleinternalcomponentsmapper.messaging.NativeCmsPublicationEventsListener;
import com.ft.methodearticleinternalcomponentsmapper.resources.MapResource;
import com.ft.methodearticleinternalcomponentsmapper.transformation.BodyProcessingFieldTransformerFactory;
import com.ft.methodearticleinternalcomponentsmapper.transformation.InteractiveGraphicsMatcher;
import com.ft.methodearticleinternalcomponentsmapper.transformation.InternalComponentsMapper;
import com.ft.methodearticleinternalcomponentsmapper.validation.MethodeArticleValidator;
import com.ft.methodearticleinternalcomponentsmapper.validation.MethodeContentPlaceholderValidator;
import com.ft.platform.dropwizard.AdvancedHealthCheck;
import com.ft.platform.dropwizard.AdvancedHealthCheckBundle;
import com.ft.platform.dropwizard.DefaultGoodToGoChecker;
import com.ft.platform.dropwizard.GoodToGoBundle;
import com.sun.jersey.api.client.Client;
import io.dropwizard.Application;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import javax.servlet.DispatcherType;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class MethodeArticleInternalComponentsMapperApplication extends Application<MethodeArticleInternalComponentsMapperConfiguration> {

    public static void main(final String[] args) throws Exception {
        new MethodeArticleInternalComponentsMapperApplication().run(args);
    }

    @Override
    public void initialize(final Bootstrap<MethodeArticleInternalComponentsMapperConfiguration> bootstrap) {
        bootstrap.addBundle(new AdvancedHealthCheckBundle());
        bootstrap.addBundle(new GoodToGoBundle(new DefaultGoodToGoChecker()));
    }

    @Override
    public void run(final MethodeArticleInternalComponentsMapperConfiguration configuration,
                    final Environment environment) throws Exception {
        org.slf4j.LoggerFactory.getLogger(MethodeArticleInternalComponentsMapperApplication.class)
                .info("JVM file.encoding = {}", System.getProperty("file.encoding"));

        environment.servlets().addFilter("transactionIdFilter", new TransactionIdFilter())
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/map");

        BuildInfoResource buildInfoResource = new BuildInfoResource();
        environment.jersey().register(buildInfoResource);

        MethodeMapperConfiguration mamConfiguration =
                configuration.getMethodeArticleMapperConfiguration();
        Client mamClient = configureResilientClient(
                environment,
                mamConfiguration.getEndpointConfiguration(),
                mamConfiguration.getConnectionConfiguration()
        );
        EndpointConfiguration mamEndpointConfiguration = mamConfiguration.getEndpointConfiguration();
        URI mamUri = UriBuilder
                .fromPath(mamEndpointConfiguration.getPath())
                .scheme("http")
                .host(mamEndpointConfiguration.getHost())
                .build();

        MethodeMapperConfiguration mcpmConfiguration =
                configuration.getMethodeContentPlaceholderMapperConfiguration();
        Client mcpmClient = configureResilientClient(
                environment,
                mcpmConfiguration.getEndpointConfiguration(),
                mcpmConfiguration.getConnectionConfiguration()
        );
        EndpointConfiguration mcpmEndpointConfiguration = mcpmConfiguration.getEndpointConfiguration();
        URI mcpmUri = UriBuilder
                .fromPath(mcpmEndpointConfiguration.getPath())
                .scheme("http")
                .host(mcpmEndpointConfiguration.getHost())
                .build();

        DocumentStoreApiConfiguration documentStoreApiConfiguration = configuration.getDocumentStoreApiConfiguration();
        ResilientClient documentStoreApiClient = (ResilientClient) configureResilientClient(environment, documentStoreApiConfiguration.getEndpointConfiguration(), documentStoreApiConfiguration.getConnectionConfig());
        EndpointConfiguration documentStoreApiEndpointConfiguration = documentStoreApiConfiguration.getEndpointConfiguration();
        UriBuilder documentStoreApiBuilder = UriBuilder.fromPath(documentStoreApiEndpointConfiguration.getPath()).scheme("http").host(documentStoreApiEndpointConfiguration.getHost()).port(documentStoreApiEndpointConfiguration.getPort());
        URI documentStoreUri = documentStoreApiBuilder.build();

        ConcordanceApiConfiguration concordanceApiConfiguration = configuration.getConcordanceApiConfiguration();
        Client concordanceApiClient = configureResilientClient(environment, concordanceApiConfiguration.getEndpointConfiguration(), concordanceApiConfiguration.getConnectionConfiguration());
        EndpointConfiguration concordanceApiEndpointConfiguration = concordanceApiConfiguration.getEndpointConfiguration();
        UriBuilder concordanceApiBuilder = UriBuilder.fromPath(concordanceApiEndpointConfiguration.getPath()).scheme("http").host(concordanceApiEndpointConfiguration.getHost()).port(concordanceApiEndpointConfiguration.getPort());
        URI concordanceUri = concordanceApiBuilder.build();

        InternalComponentsMapper eomFileProcessor = new InternalComponentsMapper(
                new BodyProcessingFieldTransformerFactory(documentStoreApiClient, documentStoreUri,
                        new VideoMatcher(configuration.getVideoSiteConfig()),
                        new InteractiveGraphicsMatcher(configuration.getInteractiveGraphicsWhitelist()),
                        configuration.getContentTypeTemplates(),
                        configuration.getApiHost(),
                        concordanceApiClient,
                        concordanceUri
                ).newInstance(),
                new MethodeArticleValidator(mamClient, mamUri, "methode-article-mapper"),
                new MethodeContentPlaceholderValidator(mcpmClient, mcpmUri, "methode-content-placeholder-mapper"),
                new Html5SelfClosingTagBodyProcessor()
        );

        ConsumerConfiguration consumerConfig = configuration.getConsumerConfiguration();
        MessageProducingInternalComponentsMapper msgProducingListMapper =
                new MessageProducingInternalComponentsMapper(
                        getMessageBuilder(configuration, environment),
                        configureMessageProducer(configuration.getProducerConfiguration(), environment),
                        eomFileProcessor
                );
        MessageListener listener = new NativeCmsPublicationEventsListener(
                environment.getObjectMapper(),
                msgProducingListMapper,
                consumerConfig.getSystemCode()
        );
        registerListener(
                environment, listener, consumerConfig,
                getConsumerClient(environment, consumerConfig)
        );

        registerHealthChecks(
                environment,
                buildClientHealthChecks(
                        mamClient, mamEndpointConfiguration
                )
        );

        environment.jersey().register(new MapResource(eomFileProcessor));
        environment.jersey().register(RuntimeExceptionMapper.class);
    }

    private Client configureResilientClient(
            Environment environment,
            EndpointConfiguration endpointConfiguration,
            ConnectionConfiguration connectionConfig) {

        return ResilientClientBuilder.in(environment)
                .using(endpointConfiguration)
                .withContinuationPolicy(
                        new ExponentialBackoffContinuationPolicy(
                                connectionConfig.getNumberOfConnectionAttempts(),
                                connectionConfig.getTimeoutMultiplier()
                        )
                ).withTransactionPropagation()
                .build();
    }

    private void registerHealthChecks(Environment environment,
                                      List<AdvancedHealthCheck> advancedHealthChecks) {

        HealthCheckRegistry healthChecks = environment.healthChecks();
        for (AdvancedHealthCheck hc : advancedHealthChecks) {
            healthChecks.register(hc.getName(), hc);
        }
    }

    private List<AdvancedHealthCheck> buildClientHealthChecks(Client methodeArticleMapperClient,
                                                              EndpointConfiguration methodeArticleMapperEndpointConfiguration) {

        List<AdvancedHealthCheck> healthchecks = new ArrayList<>();
        healthchecks.add(new RemoteServiceHealthCheck(
                "Methode Article Mapper",
                methodeArticleMapperClient,
                methodeArticleMapperEndpointConfiguration.getHost(),
                methodeArticleMapperEndpointConfiguration.getPort(),
                "/__health",
                "methode-article-mapper",
                1,
                "Internal components of newly published Methode articles will not be available from the InternalContent API",
                "https://dewey.ft.com/up-maicm.html")
        );
        return healthchecks;
    }

    private MessageBuilder getMessageBuilder(
            MethodeArticleInternalComponentsMapperConfiguration configuration, Environment environment) {
        return new MessageBuilder(
                UriBuilder.fromUri(configuration.getContentUriPrefix()).path("{uuid}"),
                configuration.getConsumerConfiguration().getSystemCode(),
                environment.getObjectMapper()
        );
    }

    private Client getConsumerClient(Environment environment, ConsumerConfiguration config) {
        JerseyClientConfiguration jerseyConfig = config.getJerseyClientConfiguration();
        jerseyConfig.setGzipEnabled(false);
        jerseyConfig.setGzipEnabledForRequests(false);

        return ResilientClientBuilder.in(environment)
                .using(jerseyConfig)
                .usingDNS()
                .named("consumer-client")
                .build();
    }

    protected MessageProducer configureMessageProducer(ProducerConfiguration config,
                                                       Environment environment) {
        JerseyClientConfiguration jerseyConfig = config.getJerseyClientConfiguration();
        jerseyConfig.setGzipEnabled(false);
        jerseyConfig.setGzipEnabledForRequests(false);

        Client producerClient = ResilientClientBuilder.in(environment)
                .using(jerseyConfig)
                .usingDNS()
                .named("producer-client")
                .build();

        final QueueProxyProducer.BuildNeeded queueProxyBuilder = QueueProxyProducer.builder()
                .withJerseyClient(producerClient)
                .withQueueProxyConfiguration(config.getMessageQueueProducerConfiguration());

        final QueueProxyProducer producer = queueProxyBuilder.build();

        registerProducerHealthCheck(environment, config, queueProxyBuilder);

        return producer;
    }

    protected void registerListener(
            Environment environment,
            MessageListener listener,
            ConsumerConfiguration config,
            Client consumerClient) {

        final MessageQueueConsumerInitializer messageQueueConsumerInitializer =
                new MessageQueueConsumerInitializer(
                        config.getMessageQueueConsumerConfiguration(),
                        listener,
                        consumerClient
                );
        environment.lifecycle().manage(messageQueueConsumerInitializer);

        registerConsumerHealthCheck(environment, config, messageQueueConsumerInitializer);
    }

    private void registerProducerHealthCheck(
            Environment environment,
            ProducerConfiguration config,
            QueueProxyProducer.BuildNeeded queueProxyBuilder) {

        environment.healthChecks().register("KafkaProxyProducer",
                new CanConnectToMessageQueueProducerProxyHealthcheck(
                        queueProxyBuilder.buildHealthcheck(),
                        config.getHealthcheckConfiguration(),
                        environment.metrics()
                )
        );
    }

    private void registerConsumerHealthCheck(
            Environment environment,
            ConsumerConfiguration config,
            MessageQueueConsumerInitializer messageQueueConsumerInitializer) {

        environment.healthChecks().register("KafkaProxyConsumer",
                messageQueueConsumerInitializer.buildPassiveConsumerHealthcheck(
                        config.getHealthcheckConfiguration(), environment.metrics()
                )
        );
    }
}
