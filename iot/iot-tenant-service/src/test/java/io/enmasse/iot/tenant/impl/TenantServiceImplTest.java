/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.iot.tenant.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.util.TenantConstants;
import org.eclipse.hono.util.TenantObject;
import org.eclipse.hono.util.TenantResult;
import org.junit.jupiter.api.Test;

import io.enmasse.iot.model.v1.IoTProject;
import io.enmasse.iot.model.v1.IoTProjectBuilder;
import io.enmasse.iot.utils.MoreFutures;
import io.opentracing.noop.NoopTracerFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class TenantServiceImplTest {

    private final TenantServiceImpl service;

    public TenantServiceImplTest() {
        this.service = new TenantServiceImpl();
        this.service.configuration = new TenantServiceConfigProperties();
        this.service.tracer = NoopTracerFactory.create();
    }

    @Test
    public void testInvalidTrustAnchor () throws Exception {

        final X500Principal name = new X500Principal("CN=Foo,O=Bar,C=Baz");

        // The trust anchor does not have any not-before and not-after dates
        final JsonObject configuration = new JsonObject()
                .put(TenantConstants.FIELD_PAYLOAD_TRUSTED_CA,
                        new JsonArray()
                        .add(new JsonObject()
                                .put(TenantConstants.FIELD_PAYLOAD_SUBJECT_DN, name.getName())
                        ));

        final IoTProject project = new IoTProjectBuilder()
                .withNewMetadata()
                .withNamespace("ns")
                .withName("iot")
                .endMetadata()
                .withNewStatus()
                .withNewAccepted()
                .withConfiguration(configuration)
                .endAccepted()
                .endStatus()
                .build();

        // mock discovery of project

        this.service.onAdd(project);

        // call service method

        final Future<TenantResult<JsonObject>> result = this.service.get(name);

        // assert result

        final TenantResult<JsonObject> r = MoreFutures.get(result);
        assertNotNull(r);

        final TenantObject tenant = r.getPayload().mapTo(TenantObject.class);
        assertNotNull(tenant);
        assertTrue(tenant.getTrustAnchors().isEmpty());
    }

}