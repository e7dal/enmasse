/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.enmasse.api.model.MessagingEndpointBuilder;
import io.enmasse.api.model.MessagingEndpointPort;
import io.enmasse.api.model.MessagingInfrastructureBuilder;
import io.enmasse.api.model.MessagingProjectBuilder;
import io.enmasse.iot.model.v1.AdapterConfig;
import io.enmasse.iot.model.v1.AdapterConfigFluent;
import io.enmasse.iot.model.v1.AdaptersConfig;
import io.enmasse.iot.model.v1.AdaptersConfigFluent;
import io.enmasse.iot.model.v1.AdaptersConfigFluent.AmqpNested;
import io.enmasse.iot.model.v1.AdaptersConfigFluent.HttpNested;
import io.enmasse.iot.model.v1.AdaptersConfigFluent.LoraWanNested;
import io.enmasse.iot.model.v1.AdaptersConfigFluent.MqttNested;
import io.enmasse.iot.model.v1.AdaptersConfigFluent.SigfoxNested;
import io.enmasse.iot.model.v1.ConfigConditionType;
import io.enmasse.iot.model.v1.IoTInfrastructure;
import io.enmasse.iot.model.v1.IoTInfrastructureBuilder;
import io.enmasse.iot.model.v1.IoTInfrastructureFluent.SpecNested;
import io.enmasse.iot.model.v1.IoTInfrastructureSpec;
import io.enmasse.iot.model.v1.IoTInfrastructureSpecFluent.AdaptersNested;
import io.enmasse.iot.model.v1.IoTTenant;
import io.enmasse.iot.model.v1.IoTTenantBuilder;
import io.enmasse.iot.model.v1.TenantConditionType;
import io.enmasse.systemtest.Endpoint;
import io.enmasse.systemtest.Environment;
import io.enmasse.systemtest.amqp.AmqpClient;
import io.enmasse.systemtest.executor.Exec;
import io.enmasse.systemtest.framework.TestPlanInfo;
import io.enmasse.systemtest.iot.IoTTestSession.Builder.PreDeployProcessor;
import io.enmasse.systemtest.logs.GlobalLogCollector;
import io.enmasse.systemtest.platform.KubeCMDClient;
import io.enmasse.systemtest.platform.Kubernetes;
import io.enmasse.systemtest.utils.TestUtils;
import io.enmasse.systemtest.utils.ThrowingCallable;
import io.enmasse.systemtest.utils.ThrowingConsumer;
import io.enmasse.systemtest.utils.ThrowingFunction;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemTrustOptions;
import org.assertj.core.api.SoftAssertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.enmasse.systemtest.amqp.AmqpConnectOptions.defaultQueue;
import static io.enmasse.systemtest.framework.condition.OpenShiftVersion.OCP4;
import static io.enmasse.systemtest.iot.DeviceManagementApi.createManagementServiceAccount;
import static io.enmasse.systemtest.iot.IoTTestSession.Adapter.AMQP;
import static io.enmasse.systemtest.iot.IoTTestSession.Adapter.HTTP;
import static io.enmasse.systemtest.iot.IoTTestSession.Adapter.MQTT;
import static io.enmasse.systemtest.platform.Kubernetes.isOpenShiftCompatible;
import static io.enmasse.systemtest.utils.Conditions.condition;
import static io.enmasse.systemtest.utils.Conditions.gone;
import static io.enmasse.systemtest.utils.TestUtils.waitUntilConditionOrFail;
import static java.lang.System.lineSeparator;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

public final class IoTTestSession implements IoTTestContext {

    private static final Logger log = LoggerFactory.getLogger(IoTTestSession.class);
    private static final String IOT_TENANT_NAMESPACE = "iot-systemtests";

    public enum Adapter {
        AMQP(AdaptersConfig::getAmqp) {
            @Override
            public IoTInfrastructureBuilder edit(IoTInfrastructureBuilder infra, Consumer<? super AdapterConfigFluent<?>> consumer) {
                return Adapter.editAdapter(infra, AdaptersConfigFluent::editOrNewAmqp, AmqpNested::endAmqp, a -> {
                    consumer.accept(a);
                    return a;
                });
            }
        },
        HTTP(AdaptersConfig::getHttp) {
            @Override
            public IoTInfrastructureBuilder edit(IoTInfrastructureBuilder infra, Consumer<? super AdapterConfigFluent<?>> consumer) {
                return Adapter.editAdapter(infra, AdaptersConfigFluent::editOrNewHttp, HttpNested::endHttp, a -> {
                    consumer.accept(a);
                    return a;
                });
            }
        },
        MQTT(AdaptersConfig::getMqtt) {
            @Override
            public IoTInfrastructureBuilder edit(IoTInfrastructureBuilder infra, Consumer<? super AdapterConfigFluent<?>> consumer) {
                return Adapter.editAdapter(infra, AdaptersConfigFluent::editOrNewMqtt, MqttNested::endMqtt, a -> {
                    consumer.accept(a);
                    return a;
                });
            }
        },
        SIGFOX(AdaptersConfig::getSigfox) {
            @Override
            public IoTInfrastructureBuilder edit(IoTInfrastructureBuilder infra, Consumer<? super AdapterConfigFluent<?>> consumer) {
                return Adapter.editAdapter(infra, AdaptersConfigFluent::editOrNewSigfox, SigfoxNested::endSigfox, a -> {
                    consumer.accept(a);
                    return a;
                });
            }
        },
        LORAWAN(AdaptersConfig::getLoraWan) {
            @Override
            public IoTInfrastructureBuilder edit(IoTInfrastructureBuilder infra, Consumer<? super AdapterConfigFluent<?>> consumer) {
                return Adapter.editAdapter(infra, AdaptersConfigFluent::editOrNewLoraWan, LoraWanNested::endLoraWan, a -> {
                    consumer.accept(a);
                    return a;
                });
            }
        },
        ;

        private static <X extends AdapterConfigFluent<X>> IoTInfrastructureBuilder editAdapter(
                final IoTInfrastructureBuilder infra,
                final Function<AdaptersNested<SpecNested<IoTInfrastructureBuilder>>, X> editOrNew,
                final Function<X, AdaptersNested<SpecNested<IoTInfrastructureBuilder>>> end,
                final Function<X, X> editor) {

            final AdaptersNested<SpecNested<IoTInfrastructureBuilder>> a = infra
                    .editOrNewSpec()
                    .editOrNewAdapters();

            return editOrNew
                    .andThen(editor)
                    .andThen(end)
                    .apply(a)

                    .endAdapters()
                    .endSpec();

        }

        Adapter(final Function<AdaptersConfig, ? extends AdapterConfig> getter) {
            this.getter = getter;
        }

        private final Function<AdaptersConfig, ? extends AdapterConfig> getter;

        public abstract IoTInfrastructureBuilder edit(IoTInfrastructureBuilder infra, Consumer<? super AdapterConfigFluent<?>> consumer);

        public Optional<AdapterConfig> getConfig(IoTInfrastructure infra) {
            return Optional
                    .ofNullable(infra)
                    .map(IoTInfrastructure::getSpec)
                    .map(IoTInfrastructureSpec::getAdapters)
                    .map(getter);
        }

        public <T> T apply(final IoTInfrastructure infra, final Function<Optional<AdapterConfig>, T> function) {
            return function.apply(getConfig(infra));
        }

        public IoTInfrastructureBuilder enable(final IoTInfrastructureBuilder infra, boolean enabled) {
            return edit(infra, a -> a.withEnabled(enabled));
        }

        public IoTInfrastructureBuilder enable(final IoTInfrastructureBuilder infra) {
            return enable(infra, true);
        }

        public IoTInfrastructureBuilder disable(final IoTInfrastructureBuilder infra) {
            return enable(infra, false);
        }

        /**
         * Check if an adapter is enabled.
         * <p>
         * The adapter is only disabled if the field {@code enabled} is set to {@code value}.
         * In all other cases, like null values or missing fields, the adapter is considered enabled.
         *
         * @param infra The configuration to check.
         * @return {@code true} if the adapter is enabled, {@code false} otherwise.
         */
        public boolean isEnabled(final IoTInfrastructure infra) {
            return getConfig(infra)
                    .map(adapterConfig -> adapterConfig.getEnabled() == null || Boolean.TRUE.equals(adapterConfig.getEnabled()))
                    .orElse(Boolean.TRUE);
        }

        public String getResourceName() {
            return String.format("iot-%s-adapter", name().toLowerCase());
        }
    }

    @FunctionalInterface
    public interface Code {
        void run(IoTTestSession session) throws Exception;
    }

    private final Vertx vertx;
    private final IoTInfrastructure infra;
    private final Consumer<Throwable> exceptionHandler;
    private final Set<String> defaultTlsVersions;
    private final List<ThrowingCallable> cleanup;

    private IoTTestContext defaultTenant;

    private IoTTestSession(
            final Vertx vertx,
            final IoTInfrastructure infra,
            final Consumer<Throwable> exceptionHandler,
            final Set<String> defaultTlsVersions,
            final List<ThrowingCallable> cleanup) {

        this.vertx = vertx;
        this.infra = infra;

        this.exceptionHandler = exceptionHandler;
        this.defaultTlsVersions = defaultTlsVersions;
        this.cleanup = cleanup;

    }

    @Override
    public IoTInfrastructure getInfra() {
        return this.defaultTenant.getInfra();
    }

    @Override
    public IoTTenant getTenant() {
        return this.defaultTenant.getTenant();
    }

    private HttpAdapterClient createHttpAdapterClient(final PrivateKey key, final X509Certificate certificate, final Set<String> tlsVersions) throws Exception {

        var endpoint = Kubernetes.getInstance().getExternalEndpoint("iot-http-adapter");
        var result = new HttpAdapterClient(this.vertx, endpoint, key, certificate, tlsVersions);
        this.cleanup.add(result::close);

        return result;

    }

    private HttpAdapterClient createHttpAdapterClient(final String authId, final String password, final Set<String> tlsVersions) throws Exception {

        var endpoint = Kubernetes.getInstance().getExternalEndpoint("iot-http-adapter");
        var result = new HttpAdapterClient(this.vertx, endpoint, authId, getTenantId(), password, tlsVersions);
        this.cleanup.add(result::close);

        return result;

    }

    private AmqpAdapterClient createAmqpAdapterClient(final PrivateKey key, final X509Certificate certificate, final Set<String> tlsVersions)
            throws Exception {

        var endpoint = Kubernetes.getInstance().getExternalEndpoint("iot-amqp-adapter");
        var result = new AmqpAdapterClient(this.vertx, endpoint, key, certificate, tlsVersions);
        this.cleanup.add(result::close);
        result.connect();

        return result;

    }

    private AmqpAdapterClient createAmqpAdapterClient(final String authId, final String password, final Set<String> tlsVersions) throws Exception {

        var endpoint = Kubernetes.getInstance().getExternalEndpoint("iot-amqp-adapter");
        var result = new AmqpAdapterClient(this.vertx, endpoint, authId, getTenantId(), password, tlsVersions);
        this.cleanup.add(result::close);
        result.connect();

        return result;

    }

    private MqttAdapterClient createMqttAdapterClient(final String deviceId, final PrivateKey key, final X509Certificate certificate) throws Exception {

        var endpoint = Kubernetes.getInstance().getExternalEndpoint("iot-mqtt-adapter");
        var result = MqttAdapterClient.create(endpoint, deviceId, key, certificate);
        this.cleanup.add(result::close);

        return result;

    }

    private MqttAdapterClient createMqttAdapterClient(final String deviceId, final String authId, final String password) throws Exception {

        var endpoint = Kubernetes.getInstance().getExternalEndpoint("iot-mqtt-adapter");
        var result = MqttAdapterClient.create(endpoint, deviceId, authId, getTenantId(), password);
        this.cleanup.add(result::close);

        return result;

    }

    @Override
    public AmqpClient getConsumerClient() {
        return this.defaultTenant.getConsumerClient();
    }

    /**
     * Run code for the session, properly handling exceptions.
     *
     * @param code The code to run.
     * @throws Exception In case anything went wrong.
     */
    public void run(final Runnable code) throws Exception {
        run((Code) session -> code.run());
    }

    /**
     * Run code for the session, properly handling exceptions.
     *
     * @param code The code to run.
     * @throws Exception In case anything went wrong.
     */
    public void run(final Code code) throws Exception {
        /*
         * We need an inner try-catch in order to only handle the exception of the test code.
         * The exception of the deploy method is handled by the deploy method itself.
         */
        try {
            code.run(this);
        } catch (Throwable e) {
            // we need to catch Throwables as unit test assertions
            // are based on Error instead of Exception.
            if (log.isDebugEnabled()) {
                log.debug("Caught exception during test", e);
            } else {
                log.error("Caught exception during test, running exception handler");
            }
            this.exceptionHandler.accept(e);
            throw e;
        }
    }

    /**
     * Close the instance and run remaining cleanup tasks.
     */
    @Override
    public void close() throws Exception {

        log.info("Cleaning up test instance: {}/{}", this.infra.getMetadata().getNamespace(), this.infra.getMetadata().getName());

        var e = cleanup(this.cleanup, null);
        if (e != null) {
            throw e;
        }

    }

    public static final class Builder {

        @FunctionalInterface
        interface BuildProcessor<T, X extends Throwable> {
            T process(IoTInfrastructure infra, IoTTenant tenant) throws X;
        }

        private IoTTenantBuilder tenant;
        private final List<PreDeployProcessor> preDeploy = new LinkedList<>();

        private IoTInfrastructureBuilder infra;
        private Set<String> defaultTlsVersions;

        private Consumer<Throwable> exceptionHandler = IoTTestSession::defaultExceptionHandler;
        private Consumer<TenantBuilder> defaultTenantCustomizer;

        private Builder(final IoTInfrastructureBuilder infra, final IoTTenantBuilder tenant) {
            this.infra = infra;
            this.tenant = tenant;
        }

        public Builder infra(final ThrowingFunction<IoTInfrastructureBuilder, IoTInfrastructureBuilder> infraCustomizer) throws Exception {
            this.infra = infraCustomizer.apply(this.infra);
            return this;
        }

        public Builder defaultTlsVersions(final String... defaultTlsVersions) {
            this.defaultTlsVersions = defaultTlsVersions == null || defaultTlsVersions.length == 0 ? null : Set.of(defaultTlsVersions);
            return this;
        }

        public Builder defaultTlsVersions(final Set<String> defaultTlsVersions) {
            this.defaultTlsVersions = defaultTlsVersions == null ? null : Set.copyOf(defaultTlsVersions);
            return this;
        }

        public Builder tenant(final ThrowingFunction<IoTTenantBuilder, IoTTenantBuilder> customizer) throws Exception {
            this.tenant = customizer.apply(this.tenant);
            return this;
        }

        public Builder preDeploy(final PreDeployProcessor processor) {
            this.preDeploy.add(processor);
            return this;
        }

        public Builder adapters(final EnumSet<Adapter> enable) {

            for (var adapter : EnumSet.complementOf(enable)) {
                this.infra = adapter.disable(this.infra);
            }

            for (var adapter : enable) {
                this.infra = adapter.enable(this.infra);
            }

            return this;

        }

        public Builder exceptionHandler(final Consumer<Throwable> exceptionHandler) {
            this.exceptionHandler = exceptionHandler;
            return this;
        }

        /**
         * Ensure only the provided adapters are enabled.
         *
         * @param adapters The adapter to enable.
         */
        public Builder adapters(final Adapter... adapters) {
            return adapters(EnumSet.copyOf(Arrays.asList(adapters)));
        }

        public Builder defaultTenantCustomizer(final Consumer<TenantBuilder> customizer) {
            this.defaultTenantCustomizer = customizer;
            return this;
        }

        /**
         * Deploy the setup, run the code and clean up.
         *
         * @param code The code to run.
         * @throws Exception if anything goes wrong.
         */
        public void run(final Code code) throws Exception {
            try (IoTTestSession session = deploy()) {
                session.run(code);
            }
        }

        public interface PreDeployContext {
            void addCleanup(ThrowingCallable cleanup);
        }

        @FunctionalInterface
        public interface PreDeployProcessor {
            void preDeploy(PreDeployContext context, IoTInfrastructureBuilder infra, IoTTenantBuilder tenant) throws Exception;
        }

        /**
         * Deploy the test session infrastructure.
         *
         * @return The test session for further processing.
         * @throws Exception in case the deployment fails.
         */
        public IoTTestSession deploy() throws Exception {

            return trackingCleanup(this.exceptionHandler, cleanup -> {

                // pre deploy

                for (final PreDeployProcessor processor : this.preDeploy) {
                    processor.preDeploy(new PreDeployContext() {
                        @Override
                        public void addCleanup(final ThrowingCallable cleanupTask) {
                            cleanup.add(cleanupTask);
                        }
                    }, this.infra, this.tenant);
                }

                // build objects

                var infra = this.infra.build();
                log.info("Final infrastructure configuration:{} {}", lineSeparator(), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(infra));

                /*
                 * Create resources: in order to properly clean up, register cleanups first, then perform the operation
                 */

                var infraNamespace = infra.getMetadata().getNamespace();

                // create device manager role, we do not clean it up

                createManagementServiceAccount(infraNamespace);

                // deploy certificates, we do not clean them up

                deployDefaultCerts(infraNamespace);

                // create messaging infrastructure

                var messagingInfrastructure = new MessagingInfrastructureBuilder()
                        .withNewMetadata()
                        .withNamespace(infraNamespace)
                        .withName("default")
                        .endMetadata()

                        .withNewSpec()
                        .withNewBroker().endBroker()
                        .withNewRouter().endRouter()
                        .endSpec()

                        .build();
                createDefaultResource(Kubernetes::messagingInfrastructures, messagingInfrastructure, "Ready", cleanup);

                // create vertx context

                log.info("Creating vert.x instance");
                final Vertx vertx = Vertx.factory.vertx();
                cleanup.add(vertx::close);

                // create IoT config

                createDefaultResource(Kubernetes::iotInfrastructures, infra, ConfigConditionType.READY, cleanup,
                        IoTUtils::assertIoTConfigReady,
                        IoTUtils::assertIoTConfigGone
                );

                // create result

                var result = new IoTTestSession(vertx, infra, this.exceptionHandler, this.defaultTlsVersions, new ArrayList<>(cleanup));

                /*
                 * We handed off responsibility of cleaning up our close list to the "test session". So we can clean
                 * the list, and add a reference to the "iot test session", which knows now what to clean up so far.
                 */

                cleanup.clear();
                cleanup.add(result::close);

                // create default tenant

                var defaultTenantBuilder = result
                        .newTenant(this.tenant)
                        .createNamespace(true);
                if (this.defaultTenantCustomizer != null) {
                    this.defaultTenantCustomizer.accept(defaultTenantBuilder);
                }
                var defaultTenant = defaultTenantBuilder.deploy();
                result.setDefaultTenant(defaultTenant);

                // done

                return result;

            });
        }

    }

    public class TenantBuilder {

        private final IoTTenantBuilder tenant;
        private final Consumer<Throwable> exceptionHandler;
        private final Set<String> defaultTlsVersions;

        private Set<String> consumerTlsVersions;
        private boolean awaitReady = true;
        private boolean createNamespace = false;

        private ThrowingConsumer<MessagingProjectBuilder> projectCustomizer;
        private ThrowingConsumer<MessagingEndpointBuilder> endpointCustomizer;

        private TenantBuilder(
                final IoTTenantBuilder tenant,
                final Consumer<Throwable> exceptionHandler,
                final Set<String> defaultTlsVersions
        ) {
            this.tenant = tenant;
            this.exceptionHandler = exceptionHandler;
            this.defaultTlsVersions = defaultTlsVersions;
        }

        public TenantBuilder tenant(final ThrowingConsumer<IoTTenantBuilder> customizer) throws Exception {
            customizer.accept(this.tenant);
            return this;
        }

        public TenantBuilder project(final ThrowingConsumer<MessagingProjectBuilder> projectCustomizer) {
            this.projectCustomizer = projectCustomizer;
            return this;
        }

        public TenantBuilder endpoint(final ThrowingConsumer<MessagingEndpointBuilder> endpointCustomizer) {
            this.endpointCustomizer = endpointCustomizer;
            return this;
        }

        public TenantBuilder consumerTlsVersions(final String... consumerTlsVersions) {
            return consumerTlsVersions(consumerTlsVersions != null ? Set.of(consumerTlsVersions) : null);
        }

        public TenantBuilder consumerTlsVersions(final Set<String> consumerTlsVersions) {
            this.consumerTlsVersions = consumerTlsVersions;
            return this;
        }

        public TenantBuilder awaitReady(boolean enabled) {
            this.awaitReady = enabled;
            return this;
        }

        public TenantBuilder createNamespace(boolean enabled) {
            this.createNamespace = enabled;
            return this;
        }

        public TenantBuilder createNamespace() {
            return createNamespace(true);
        }

        public IoTTestContext deploy() throws Exception {

            return trackingCleanup(this.exceptionHandler, cleanup -> {

                // build the tenant object

                var tenant = this.tenant.build();

                // the tenant namespace

                var namespace = tenant.getMetadata().getNamespace();

                if (this.createNamespace) {

                    // create namespace if not created

                    if (!Environment.getInstance().skipCleanup()) {
                        cleanup.add(() -> Kubernetes.getInstance().deleteNamespace(namespace));
                    }
                    Kubernetes.getInstance().createNamespace(namespace);

                    // create messaging tenant

                    var messagingProject = createDefaultProject(namespace);
                    if (this.projectCustomizer != null) {
                        this.projectCustomizer.accept(messagingProject);
                    }
                    createDefaultResource(Kubernetes::messagingProjects, messagingProject.build(), "Ready", cleanup);

                    // create messaging endpoint

                    var messagingEndpoint = createDefaultEndpoint(namespace);
                    if (this.endpointCustomizer != null) {
                        this.endpointCustomizer.accept(messagingEndpoint);
                    }
                    createDefaultResource(Kubernetes::messagingEndpoints, messagingEndpoint.build(), "Ready", cleanup);
                }

                // create IoT tenant

                if (this.awaitReady) {
                    createDefaultResource(Kubernetes::iotTenants, tenant, TenantConditionType.READY, cleanup);
                }

                // create endpoints

                var deviceRegistryEndpoint = IoTUtils.getDeviceRegistryManagementEndpoint();

                var registryClient = new DeviceRegistryClient(
                        IoTTestSession.this.vertx,
                        deviceRegistryEndpoint,
                        this.defaultTlsVersions);
                cleanup.add(registryClient::close);

                var credentialsClient = new CredentialsRegistryClient(
                        IoTTestSession.this.vertx,
                        deviceRegistryEndpoint,
                        this.defaultTlsVersions);
                cleanup.add(credentialsClient::close);

                // create user

                // FIXME: when users are back, we need to create a user for telemetry, events, and command and control.
                /*

                var tenantId = getTenantId(project);

                final AddressSpace addressSpace = Kubernetes.getInstance().getAddressSpaceClient().inNamespace(project.getMetadata().getNamespace())
                        .withName(project.getSpec().getDownstreamStrategy().getManagedStrategy().getAddressSpace().getName()).get();

                final UserCredentials credentials = new UserCredentials(UUID.randomUUID().toString(), UUID.randomUUID().toString());
                final User user = UserUtils.createUserResource(credentials)

                        .editOrNewMetadata()
                        .withNamespace(addressSpace.getMetadata().getNamespace())
                        .withName(addressSpace.getMetadata().getName() + "." + credentials.getUsername())
                        .endMetadata()

                        .editSpec()
                        .withAuthorization(
                                Collections.singletonList(new UserAuthorizationBuilder()
                                        .withAddresses(
                                                "telemetry" + "/" + tenantId,
                                                "telemetry" + "/" + tenantId + "/*",
                                                "event" + "/" + tenantId,
                                                "event" + "/" + tenantId + "/*")
                                        .withOperations(Operation.recv)
                                        .build()))
                        .endSpec()
                        .done();

                Kubernetes.getInstance().getUserClient().inNamespace(project.getMetadata().getNamespace())
                        .createOrReplace(user);
                if (!Environment.getInstance().skipCleanup()) {
                    cleanup.add(
                            () -> Kubernetes.getInstance()
                                    .getUserClient().inNamespace(project.getMetadata().getNamespace())
                                    .withName(user.getMetadata().getName())
                                    .delete());
                }

                UserUtils.waitForUserActive(user, ofDuration(ofMinutes(1)));
                */


                // get the endpoint, we always expect a "downstream" endpoint for testing

                var messagingEndpoint = Kubernetes.messagingEndpoints(namespace)
                        .withName("downstream")
                        .get();
                var endpointHost = messagingEndpoint.getStatus().getHost();

                // eval messaging endpoint

                var port = messagingEndpoint.getStatus()
                        .getPorts().stream()
                        .filter(p -> "AMQPS".equals(p.getProtocol()))
                        .map(MessagingEndpointPort::getPort)
                        .findAny().orElseThrow(() -> new IllegalStateException("Unable to find port 'AMQPS' in endpoint status"));
                var amqpEndpoint = new Endpoint(endpointHost, port);
                var cert = messagingEndpoint.getStatus().getTls().getCaCertificate();

                // create AMQP client

                var client = new AmqpClient(
                        defaultQueue(amqpEndpoint)
                                .customizeProtonClientOptions(options -> {
                                    options
                                            .setSsl(true)
                                            .setHostnameVerificationAlgorithm("")
                                            .setTrustOptions(new PemTrustOptions()
                                                    .addCertValue(Buffer.buffer(cert)));

                                    if (this.consumerTlsVersions != null) {
                                        options.setEnabledSecureTransportProtocols(this.consumerTlsVersions);
                                    } else if (this.defaultTlsVersions != null) {
                                        options.setEnabledSecureTransportProtocols(this.defaultTlsVersions);
                                    }
                                })
                );
                cleanup.add(client::close);

                // we are all set up, register ourselves with the session

                var result = new TenantInstance(IoTTestSession.this.infra, tenant, registryClient, credentialsClient, client, cleanup);
                IoTTestSession.this.cleanup.add(result::close);

                // return result

                return result;

            });
        }

        private MessagingProjectBuilder createDefaultProject(final String namespace) {

            return new MessagingProjectBuilder()
                    .withNewMetadata()
                    .withNamespace(namespace)
                    .withName("default")
                    .endMetadata()

                    .withNewSpec().endSpec();

        }

        private MessagingEndpointBuilder createDefaultEndpoint(final String namespace) {

            var builder = new MessagingEndpointBuilder()
                    .withNewMetadata()
                    .withNamespace(namespace)
                    .withName("downstream")
                    .endMetadata();

            builder = builder.editOrNewSpec()

                    // always go for AMQPS
                    .addNewProtocol("AMQPS")

                    // with self signed certs
                    .withNewTls()
                    .withNewSelfsigned().endSelfsigned()
                    .endTls()

                    .endSpec();

            if (isOpenShiftCompatible(OCP4)) {

                builder = builder.editOrNewSpec()
                        .withNewRoute().endRoute()
                        .endSpec();

            } else {

                builder = builder.editOrNewSpec()
                        .withHost(Kubernetes.getInstance().getHost())
                        .withNewNodePort().endNodePort()
                        .endSpec();

            }

            return builder;
        }
    }

    public class TenantInstance implements IoTTestContext {

        public class Device {

            private final String deviceId;
            private String authId;
            private String password;
            private PrivateKey key;
            private X509Certificate certificate;

            private Device(String deviceId) {
                this.deviceId = deviceId;
            }

            public Device register() throws Exception {
                TenantInstance.this.registryClient.registerDevice(getTenantId(), this.deviceId);
                return this;
            }

            /**
             * Set username and password to random combination.
             *
             * @return This instance, for chained method calls.
             * @throws Exception in case anything went wrong.
             */
            public Device setPassword() throws Exception {
                return setPassword(UUID.randomUUID().toString(), UUID.randomUUID().toString());
            }

            public Device setPassword(final String authId, final String password) throws Exception {
                this.authId = authId;
                this.password = password;

                var pwd = CredentialsRegistryClient.createPlainPasswordCredentialsObject(authId, password, null);
                TenantInstance.this.credentialsClient.setCredentials(getTenantId(), this.deviceId, singletonList(pwd));
                return this;
            }

            /**
             * Use a different password then stored in the device registry (using {@link #setPassword()}.
             * <p>
             * Uses a new random password.
             *
             * @return This instance, for chained method calls.
             */
            public Device overridePassword() {
                this.password = UUID.randomUUID().toString();
                return this;
            }

            /**
             * Use a different password then stored in the device registry (using {@link #setPassword()}.
             *
             * @param password The new password to use.
             * @return This instance, for chained method calls.
             */
            public Device overridePassword(final String password) {
                this.password = password;
                return this;
            }

            public Device enableX509(io.enmasse.systemtest.iot.DeviceCertificateManager.Device device) throws Exception {
                return enableX509(device.getKey().getPrivate(), device.getCertificate());
            }

            public Device enableX509(final PrivateKey key, final X509Certificate certificate) throws Exception {
                this.key = key;
                this.certificate = certificate;

                var x509 = CredentialsRegistryClient.createX509CertificateCredentialsObject(certificate.getSubjectX500Principal().getName(), null);
                TenantInstance.this.credentialsClient.setCredentials(getTenantId(), this.deviceId, singletonList(x509));
                return this;
            }

            /**
             * Create a new http adapter client.
             *
             * @return The new instance. It will automatically be closed when the test session is being cleaned
             * up.
             */
            public HttpAdapterClient createHttpAdapterClient() throws Exception {
                return createHttpAdapterClient(null);
            }

            /**
             * Create a new http adapter client.
             *
             * @param tlsVersions The supported TLS versions.
             * @return The new instance. It will automatically be closed when the test session is being cleaned
             * up.
             */
            public HttpAdapterClient createHttpAdapterClient(final Set<String> tlsVersions) throws Exception {
                if (this.key != null) {
                    return IoTTestSession.this.createHttpAdapterClient(this.key, this.certificate, tlsVersions);
                } else {
                    return IoTTestSession.this.createHttpAdapterClient(this.authId, this.password, tlsVersions);
                }
            }

            /**
             * Create a new mqtt adapter client.
             *
             * @return The new instance. It will automatically be closed when the test session is being cleaned
             * up.
             */
            public MqttAdapterClient createMqttAdapterClient() throws Exception {
                if (key != null) {
                    return IoTTestSession.this.createMqttAdapterClient(this.deviceId, this.key, this.certificate);
                } else {
                    return IoTTestSession.this.createMqttAdapterClient(this.deviceId, this.authId, this.password);
                }
            }

            /**
             * Create a new AMQP adapter client.
             *
             * @return The new instance. It will automatically be closed when the test session is being cleaned
             * up.
             */
            public AmqpAdapterClient createAmqpAdapterClient() throws Exception {
                return createAmqpAdapterClient(null);
            }

            /**
             * Create a new AMQP adapter client.
             *
             * @param tlsVersions The supported TLS versions.
             * @return The new instance. It will automatically be closed when the test session is being cleaned
             * up.
             */
            public AmqpAdapterClient createAmqpAdapterClient(final Set<String> tlsVersions) throws Exception {
                if (this.key != null) {
                    return IoTTestSession.this.createAmqpAdapterClient(this.key, this.certificate, tlsVersions);
                } else {
                    return IoTTestSession.this.createAmqpAdapterClient(this.authId, this.password, tlsVersions);
                }
            }

            public String getTenantId() {
                return IoTTestSession.this.getTenantId();
            }

            public String getDeviceId() {
                return this.deviceId;
            }
        }

        private final IoTInfrastructure infra;
        private final IoTTenant tenant;
        private final DeviceRegistryClient registryClient;
        private final CredentialsRegistryClient credentialsClient;
        private final AmqpClient consumerClient;
        private final List<ThrowingCallable> cleanup;
        private final AtomicBoolean closed = new AtomicBoolean();

        public TenantInstance(
                final IoTInfrastructure infra,
                final IoTTenant tenant,
                final DeviceRegistryClient registryClient,
                final CredentialsRegistryClient credentialsClient,
                final AmqpClient consumerClient,
                final List<ThrowingCallable> cleanup) {

            this.infra = infra;
            this.tenant = tenant;

            this.registryClient = registryClient;
            this.credentialsClient = credentialsClient;
            this.consumerClient = consumerClient;

            this.cleanup = cleanup;

        }

        @Override
        public IoTInfrastructure getInfra() {
            return this.infra;
        }

        @Override
        public IoTTenant getTenant() {
            return this.tenant;
        }

        @Override
        public AmqpClient getConsumerClient() {
            return this.consumerClient;
        }

        @Override
        public Device newDevice(final String deviceId) {
            return new Device(deviceId);
        }

        @Override
        public void close() throws Exception {

            if (closed.getAndSet(true)) {
                return;
            }

            log.info("Cleaning up tenant instance: {}/{}", this.tenant.getMetadata().getNamespace(), this.tenant.getMetadata().getName());

            var e = cleanup(this.cleanup, null);
            if (e != null) {
                throw e;
            }

        }
    }

    /**
     * Create a new test session builder.
     *
     * @param infraNamespace The namespace for the IoTInfrastructure.
     * @param isOpenshiftFour Create configuration for OCP4.
     * @return The new instance.
     */
    public static IoTTestSession.Builder create(final String infraNamespace, final boolean isOpenshiftFour) {

        // create new default IoT infrastructure

        var infra = createDefaultInfrastructure(infraNamespace, isOpenshiftFour);

        // we use the same name for the IoTTenant and the AddressSpace

        var name = Names.randomName();

        // create new default tenant setup

        var tenant = createDefaultTenant(IOT_TENANT_NAMESPACE, name);

        // done

        return new Builder(infra, tenant);
    }

    /**
     * Create a basic test session builder.
     * <p>
     * If you want a ready-to-run configuration, use {@link #createDefault()}.
     *
     * @return The new builder, missing services and undeployed.
     */
    public static IoTTestSession.Builder create() {
        return create(
                Kubernetes.getInstance().getInfraNamespace(),
                isOpenShiftCompatible(OCP4));
    }

    /**
     * Create a default, ready-to-run, setup.
     *
     * @return The new builder, still undeployed.
     */
    public static IoTTestSession.Builder createDefault() {
        return create()
                .preDeploy(withDefaultServices());
    }

    /**
     * Create a new default infrastructure, using the default namespace.
     * <p>
     * The default namespace is evaluated by a call to
     * {@link Kubernetes#getInfraNamespace()}, which requires an active Kubernetes
     * environment.
     */
    public static IoTInfrastructureBuilder createDefaultInfrastructure() {
        return createDefaultInfrastructure(
                Kubernetes.getInstance().getInfraNamespace(),
                isOpenShiftCompatible(OCP4));
    }

    static IoTInfrastructureBuilder createDefaultInfrastructure(final String namespace, final boolean isOpenshiftFour) {

        var infra = new IoTInfrastructureBuilder()
                .withNewMetadata()
                .withName("default")
                .withNamespace(namespace)
                .endMetadata();

        // enable routes / load balancers by default

        infra = infra.editOrNewSpec()
                .withEnableDefaultRoutes(true)
                .endSpec();

        // configure logging

        infra = infra.editOrNewSpec()
                .withNewLogging()
                .withNewLevel("info")
                .addToLoggers("org.eclipse.hono", "debug")
                .addToLoggers("io.enmasse", "debug")
                .endLogging()
                .endSpec();

        if (isOpenshiftFour) {

            // enable service CA by default

            infra = infra
                    .editOrNewSpec()
                    .withNewInterServiceCertificates()
                    .withNewServiceCAStrategy()
                    .endServiceCAStrategy()
                    .endInterServiceCertificates()
                    .endSpec();

            // switch to provided key/cert for adapters that needs it
            // MQTT: needs passthrough routes because MQTT is not HTTP
            // HTTP: needs passthrough routes because X.509 client certs are being used

            infra = useSystemtestKeys(infra, HTTP, MQTT, AMQP);

        } else {

            // fall back to manual secrets for inter-service communication

            final Map<String, String> secrets = new HashMap<>();
            secrets.put("iot-auth-service", "systemtests-iot-auth-service-tls");
            secrets.put("iot-tenant-service", "systemtests-iot-tenant-service-tls");
            secrets.put("iot-device-connection", "systemtests-iot-device-connection-tls");
            secrets.put("iot-device-registry", "systemtests-iot-device-registry-tls");
            secrets.put("iot-mesh-inter", "systemtests-iot-mesh-inter-tls");
            secrets.put("iot-command-mesh", "systemtests-iot-command-mesh-tls");

            infra = infra
                    .editOrNewSpec()
                    .withNewInterServiceCertificates()
                    .withNewSecretCertificatesStrategy()
                    .withCaSecretName("systemtests-iot-service-ca")
                    .withServiceSecretNames(secrets)
                    .endSecretCertificatesStrategy()
                    .endInterServiceCertificates()
                    .endSpec();

            // all adapters need explicit endpoint key/certs

            infra = useSystemtestKeys(infra, Adapter.values());

        }

        return infra;
    }

    /**
     * Create a default IoT tenant object.
     *
     * <strong>Note:</strong> This method only created the instance of the Java object. It does not yet create the
     * instance in Kubernetes.
     *
     * @param namespace The namespace the object should be in.
     * @param name The name of the object
     * @return The new tenant instance, ready to be created.
     */
    static IoTTenantBuilder createDefaultTenant(final String namespace, final String name) {
        return new IoTTenantBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .endSpec();
    }

    private static IoTInfrastructureBuilder useSystemtestKeys(IoTInfrastructureBuilder infra, final Adapter... adapters) {
        for (Adapter adapter : adapters) {
            infra = adapter.edit(infra, c -> c
                    .editOrNewEndpoint()
                    .withNewSecretNameStrategy("systemtests-iot-" + adapter.name().toLowerCase() + "-adapter-tls")
                    .endEndpoint());
        }
        return infra;
    }

    private static Exception cleanup(final List<ThrowingCallable> cleanup, final Throwable initialException) {

        var resultingException = initialException;

        if (!Environment.getInstance().skipCleanup()) {

            log.info("Cleaning up resources...");

            for (ThrowingCallable f : Lists.reverse(cleanup)) {
                try {
                    f.call();
                } catch (Throwable e) {
                    if (resultingException == null) {
                        resultingException = e;
                    } else {
                        resultingException.addSuppressed(e);
                    }
                }
            }

            log.info("Cleaning up resources... done!");

        } else {

            log.warn("Skipping resource cleanup!");

        }

        // log

        if (resultingException != null) {
            if (initialException == null) {
                log.info("Reporting initial exception", resultingException);
            } else {
                log.info("Cleanup resulting in error", resultingException);
            }
        }

        // now throw

        if (resultingException == null || resultingException instanceof Exception) {
            // return the Exception (or null)
            return (Exception) resultingException;
        } else {
            // return Throwable wrapped in exception
            return new Exception(resultingException);
        }
    }

    private void setDefaultTenant(final IoTTestContext defaultTenant) {
        this.defaultTenant = defaultTenant;
    }

    private TenantBuilder newTenant(final IoTTenantBuilder tenant) {
        return new TenantBuilder(tenant, this.exceptionHandler, this.defaultTlsVersions);
    }

    public TenantBuilder newTenant(final String namespace, final String name) {
        return newTenant(createDefaultTenant(namespace, name));
    }

    /**
     * Start creating a new device.
     *
     * @param deviceId The ID of the device to create.
     * @return The new device creation instance. The device will only be created when the
     * {@link TenantInstance.Device#register()} method is being called.
     */
    @Override
    public TenantInstance.Device newDevice(final String deviceId) {
        return this.defaultTenant.newDevice(deviceId);
    }

    /**
     * Add some code to the cleanup handler.
     *
     * @param cleanup The code to call.
     */
    public void addCleanup(final ThrowingCallable cleanup) {
        this.cleanup.add(cleanup);
    }

    public Vertx getVertx() {
        return this.vertx;
    }

    protected static void defaultExceptionHandler(final Throwable error) {

        if (Environment.getInstance().isSkipSaveState()) {
            return;
        }

        var test = TestPlanInfo.getInstance().getActualTest();
        if (test == null) {
            test = TestPlanInfo.getInstance().getActualTestClass();
        }

        if (test != null) {
            GlobalLogCollector.saveInfraState(TestUtils.getFailedTestLogsPath(test));
        } else {
            log.error("Unable to log system test failure, no TestInfo details", error);
        }

    }

    /**
     * Add a pre-deploy step to deploy the default service configuration.
     * <p>
     * This will effectively call {@link DefaultDeviceRegistry#newDefaultInstance()} and schedule a call
     * to {@link DefaultDeviceRegistry#deleteDefaultServer()} for cleanup.
     */
    public static PreDeployProcessor withDefaultServices() {
        return (context, infra, tenant) -> {
            try {

                if (!Environment.getInstance().isSkipDeployPostgresql()) {

                    if (!Environment.getInstance().skipUninstall()) {
                        context.addCleanup(DefaultDeviceRegistry::deleteDefaultServer);
                    }

                    infra
                            .editOrNewSpec()
                            .withServices(DefaultDeviceRegistry.newDefaultInstance())
                            .endSpec();
                }

            } catch (Exception e) {
                throw new RuntimeException("Failed to deploy device registry backend", e);
            }
        };
    }

    private static void deployDefaultCerts(final String namespace) {

        log.info("Deploying default certificates");

        final Path examplesIoT = Paths.get(Environment.getInstance().getTemplatesPath())
                .resolve("install/components/iot/examples");

        Exec.executeAndCheck(examplesIoT.resolve("k8s-tls/create").toAbsolutePath().toString());

        // deploy will try to undeploy first, so it can always be called
        Exec.executeAndCheck(
                singletonList(examplesIoT.resolve("k8s-tls/deploy").toAbsolutePath().toString()),
                60_000, true, true,
                Map.of(
                        "CLI", KubeCMDClient.getCMD(),
                        "PREFIX", "systemtests-",
                        "NAMESPACE", namespace));
    }

    private static <T extends HasMetadata, L, D> void createDefaultResource(
            final Function<String, MixedOperation<T, L, D, Resource<T, D>>> clientProvider,
            final T resource,
            final Object readyCondition,
            final List<ThrowingCallable> cleanup,
            final BiConsumer<T, SoftAssertions> assertAfterConstruction,
            final BiConsumer<T, SoftAssertions> assertAfterDeconstruction) {


        log.info("Creating {}/{}: {}/{}", resource.getApiVersion(), resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());

        var client = clientProvider.apply(resource.getMetadata().getNamespace());
        if (shouldCleanup()) {
            cleanup.add(() -> {

                log.info("Deleting {}/{}: {}/{}", resource.getApiVersion(), resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());

                var access = client.inNamespace(resource.getMetadata().getNamespace()).withName(resource.getMetadata().getName());
                access
                        .withPropagationPolicy(DeletionPropagation.FOREGROUND)
                        .delete();

                waitUntilConditionOrFail(gone(access), ofMinutes(5), ofSeconds(5));

                if (assertAfterDeconstruction != null) {
                    log.debug("Asserting deconstruction");
                    assertSoftly(softly -> {
                        assertAfterDeconstruction.accept(resource, softly);
                    });
                }

            });
        }

        client.create(resource);

        var access = client
                .inNamespace(resource.getMetadata().getNamespace())
                .withName(resource.getMetadata().getName());

        waitUntilConditionOrFail(condition(client, resource, readyCondition), ofMinutes(5), ofSeconds(5));

        log.info("Creating successful. Resource is ready.");

        if (assertAfterConstruction != null) {
            log.debug("Asserting construction");
            assertSoftly(softly -> {
                assertAfterConstruction.accept(access.get(), softly);
            });
        }

    }

    private static <T extends HasMetadata, L, D> void createDefaultResource(
            final Function<String, MixedOperation<T, L, D, Resource<T, D>>> clientProvider,
            final T resource,
            final Object readyCondition,
            final List<ThrowingCallable> cleanup) {

        createDefaultResource(clientProvider, resource, readyCondition, cleanup, null, null);

    }

    /**
     * Check if created resources should be cleaned up after the test.
     *
     * <strong>Note:</strong> In-memory resources, like communication clients, must always be cleaned up. This check is
     * only intended for persisted resources, so that the current situation can be inspected on a test failure.
     *
     * @return {@code true} if resources should be cleaned up, {@code false} otherwise.
     */
    private static boolean shouldCleanup() {
        return !Environment.getInstance().skipCleanup();
    }

    /**
     * Track cleanup during creation of resources.
     * <p>
     * This method will create a cleanup list, and then call the provided code, passing in the cleanup list. When the
     * provided code fails, it will clean up all elements in the cleanup list. If the code returns without throwing
     * an exception, the result of the code will simply be returned, and no cleanup will take place.
     *
     * @param code The code which will create a set of resources.
     * @param exceptionHandler The exception handler to call.
     * @param <R> The type of the result.
     * @return the value of the provided code.
     * @throws Exception If anything goes wrong.
     */
    private static <R> R trackingCleanup(final Consumer<Throwable> exceptionHandler, final ThrowingFunction<List<ThrowingCallable>, R> code) throws Exception {

        final List<ThrowingCallable> cleanup = new LinkedList<>();

        try {
            return code.apply(cleanup);
        } catch (Throwable e) {
            if (log.isDebugEnabled()) {
                log.debug("Caught exception during deployment", e);
            } else {
                log.info("Caught exception during deployment, running exception handler", e);
            }

            // first run exception handler
            try {
                exceptionHandler.accept(e);
            } catch (Exception e2) {
                log.info("Failed to run exception handler", e2);
                e.addSuppressed(new RuntimeException("Failed to run exception handler", e2));
            }

            // cleanup in case any creation step failed
            throw cleanup(cleanup, e);
        }

    }
}
