/*
* Copyright 2019, EnMasse authors.
* License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package resolvers

import (
	"context"
	"fmt"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/cache"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/watchers"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"testing"
	time2 "time"
)

func newTestConnectionResolver(t *testing.T) *Resolver {
	objectCache := &cache.MemdbCache{}
	err := objectCache.Init(
		cache.IndexSpecifier{
			Name:    "id",
			Indexer: &cache.UidIndex{},
		},
		cache.IndexSpecifier{
			Name: "hierarchy",
			Indexer: &cache.HierarchyIndex{
				IndexCreators: map[string]cache.HierarchicalIndexCreator{
					"Connection": watchers.ConnectionIndexCreator,
					"Link":       watchers.ConnectionLinkIndexCreator,
				},
			},
		}, cache.IndexSpecifier{
			Name:         "addressLinkHierarchy",
			AllowMissing: true,
			Indexer: &cache.HierarchyIndex{
				IndexCreators: map[string]cache.HierarchicalIndexCreator{
					"Link": watchers.AddressLinkIndexCreator,
				},
			},
		})
	assert.NoError(t, err)

	metricCache := &cache.MemdbCache{}
	err = metricCache.Init(
		cache.IndexSpecifier{
			Name:    "id",
			Indexer: cache.MetricIndex(),
		},
	)

	resolver := Resolver{}
	resolver.Cache = objectCache
	resolver.MetricCache = metricCache
	return &resolver
}

func TestQueryConnection(t *testing.T) {
	r := newTestConnectionResolver(t)
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	con := createConnection("host:1234", namespace, addressspace)
	err := r.Cache.Add(con)
	assert.NoError(t, err)

	objs, err := r.Query().Connections(context.TODO(), nil, nil, nil, nil)
	assert.NoError(t, err)

	expected := 1
	actual := objs.Total
	assert.Equal(t, expected, actual, "Unexpected number of connections")

	assert.Equal(t, con.Spec, *objs.Connections[0].Spec, "Unexpected connection spec")
	assert.Equal(t, con.ObjectMeta, *objs.Connections[0].ObjectMeta, "Unexpected connection object meta")
}

func TestQueryConnectionFilter(t *testing.T) {
	r := newTestConnectionResolver(t)
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	con1 := createConnection("host:1234", namespace, addressspace)
	con2 := createConnection("host:1235", namespace, addressspace)
	err := r.Cache.Add(con1, con2)
	assert.NoError(t, err)

	filter := fmt.Sprintf("`$.ObjectMeta.Name` = '%s'", con1.ObjectMeta.Name)
	objs, err := r.Query().Connections(context.TODO(), nil, nil, &filter, nil)
	assert.NoError(t, err)

	expected := 1
	actual := objs.Total
	assert.Equal(t, expected, actual, "Unexpected number of connections")

	assert.Equal(t, con1.Spec, *objs.Connections[0].Spec, "Unexpected connection spec")
	assert.Equal(t, con1.ObjectMeta, *objs.Connections[0].ObjectMeta, "Unexpected connection object meta")
}

func TestQueryConnectionOrder(t *testing.T) {
	r := newTestConnectionResolver(t)
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	con1 := createConnection("host:1234", namespace, addressspace)
	con2 := createConnection("host:1235", namespace, addressspace)
	err := r.Cache.Add(con1, con2)
	assert.NoError(t, err)

	orderby := "`$.ObjectMeta.Name` DESC"
	objs, err := r.Query().Connections(context.TODO(), nil, nil, nil, &orderby)
	assert.NoError(t, err)

	expected := 2
	actual := objs.Total
	assert.Equal(t, expected, actual, "Unexpected number of connections")

	assert.Equal(t, con2.Spec, *objs.Connections[0].Spec, "Unexpected connection spec")
	assert.Equal(t, con2.ObjectMeta, *objs.Connections[0].ObjectMeta, "Unexpected connection object meta")
}

func TestQueryConnectionPagination(t *testing.T) {
	r := newTestConnectionResolver(t)
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	con1 := createConnection("host:1234", namespace, addressspace)
	con2 := createConnection("host:1235", namespace, addressspace)
	con3 := createConnection("host:1236", namespace, addressspace)
	con4 := createConnection("host:1237", namespace, addressspace)
	err := r.Cache.Add(con1, con2, con3, con4)
	assert.NoError(t, err)

	objs, err := r.Query().Connections(context.TODO(), nil, nil, nil, nil)
	assert.NoError(t, err)
	assert.Equal(t, 4, objs.Total, "Unexpected number of connections")

	one := 1
	two := 2
	objs, err = r.Query().Connections(context.TODO(), nil, &one, nil,  nil)
	assert.NoError(t, err)
	assert.Equal(t, 4, objs.Total, "Unexpected number of address spaces")
	assert.Equal(t, 3, len(objs.Connections), "Unexpected number of addresses in page")
	assert.Equal(t, con2.ObjectMeta, *objs.Connections[0].ObjectMeta, "Unexpected addresses object meta")

	objs, err = r.Query().Connections(context.TODO(), &one, &two, nil,  nil)
	assert.NoError(t, err)
	assert.Equal(t, 4, objs.Total, "Unexpected number of address spaces")
	assert.Equal(t, 1, len(objs.Connections), "Unexpected number of address spaces in page")
	assert.Equal(t, con3.ObjectMeta, *objs.Connections[0].ObjectMeta, "Unexpected addresses object meta")
}

func TestQueryConnectionLinks(t *testing.T) {
	r := newTestConnectionResolver(t)
	con1 := uuid.New().String()
	con2 := uuid.New().String()
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	err := r.Cache.Add(createConnectionLink(namespace, addressspace, con1, "sender"), createConnectionLink(namespace, addressspace, con2, "sender"))
	assert.NoError(t, err)

	con := &ConnectionConsoleapiEnmasseIoV1beta1{
		ObjectMeta: &metav1.ObjectMeta{
			Name:      con1,
			UID:       types.UID(con1),
			Namespace: namespace,
		},
		Spec: &consolegraphql.ConnectionSpec{
			AddressSpace: addressspace,
		},
	}
	objs, err := r.Connection_consoleapi_enmasse_io_v1beta1().Links(context.TODO(), con, nil, nil, nil, nil)
	assert.NoError(t, err)

	expected := 1
	actual := objs.Total
	assert.Equal(t, expected, actual, "Unexpected number of links")
}

func TestQueryConnectionLinkFilter(t *testing.T) {
	r := newTestConnectionResolver(t)
	conuuid := uuid.New().String()
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	link1 := createConnectionLink(namespace, addressspace, conuuid, "sender")
	link2 := createConnectionLink(namespace, addressspace, conuuid, "sender")
	err := r.Cache.Add(link1, link2)
	assert.NoError(t, err)

	con := &ConnectionConsoleapiEnmasseIoV1beta1{
		ObjectMeta: &metav1.ObjectMeta{
			Name:      conuuid,
			UID:       types.UID(conuuid),
			Namespace: namespace,
		},
		Spec: &consolegraphql.ConnectionSpec{
			AddressSpace: addressspace,
		},
	}

	filter := fmt.Sprintf("`$.ObjectMeta.Name` = '%s'", link1.ObjectMeta.Name)
	objs, err := r.Connection_consoleapi_enmasse_io_v1beta1().Links(context.TODO(), con, nil, nil, &filter, nil)
	assert.NoError(t, err)

	expected := 1
	actual := objs.Total
	assert.Equal(t, expected, actual, "Unexpected number of links")
	assert.Equal(t, link1.Spec, *objs.Links[0].Spec, "Unexpected link spec")
	assert.Equal(t, link1.ObjectMeta, *objs.Links[0].ObjectMeta, "Unexpected link object meta")
}

func TestQueryConnectionLinkOrder(t *testing.T) {
	r := newTestConnectionResolver(t)
	conuuid := uuid.New().String()
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	link1 := createConnectionLink(namespace, addressspace, conuuid, "sender")
	link2 := createConnectionLink(namespace, addressspace, conuuid, "receiver")
	err := r.Cache.Add(link1, link2)
	assert.NoError(t, err)

	con := &ConnectionConsoleapiEnmasseIoV1beta1{
		ObjectMeta: &metav1.ObjectMeta{
			Name:      conuuid,
			UID:       types.UID(conuuid),
			Namespace: namespace,
		},
		Spec: &consolegraphql.ConnectionSpec{
			AddressSpace: addressspace,
		},
	}

	orderby := "`$.Spec.Role`"
	objs, err := r.Connection_consoleapi_enmasse_io_v1beta1().Links(context.TODO(), con, nil, nil, nil, &orderby)
	assert.NoError(t, err)

	expected := 2
	actual := objs.Total
	assert.Equal(t, expected, actual, "Unexpected number of links")
	assert.Equal(t, link2.Spec, *objs.Links[0].Spec, "Unexpected link spec")
	assert.Equal(t, link2.ObjectMeta, *objs.Links[0].ObjectMeta, "Unexpected link object meta")
}

func TestQueryConnectionLinkPaged(t *testing.T) {
	r := newTestConnectionResolver(t)
	conuuid := uuid.New().String()
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	link1 := createConnectionLink(namespace, addressspace, conuuid, "sender")
	link2 := createConnectionLink(namespace, addressspace, conuuid, "receiver")
	link3 := createConnectionLink(namespace, addressspace, conuuid, "receiver")
	link4 := createConnectionLink(namespace, addressspace, conuuid, "receiver")
	err := r.Cache.Add(link1, link2, link3, link4)
	assert.NoError(t, err)

	con := &ConnectionConsoleapiEnmasseIoV1beta1{
		ObjectMeta: &metav1.ObjectMeta{
			Name:      conuuid,
			UID:       types.UID(conuuid),
			Namespace: namespace,
		},
		Spec: &consolegraphql.ConnectionSpec{
			AddressSpace: addressspace,
		},
	}

	objs, err := r.Connection_consoleapi_enmasse_io_v1beta1().Links(context.TODO(), con, nil, nil, nil, nil)
	assert.NoError(t, err)
	assert.Equal(t, 4, objs.Total, "Unexpected number of links")

	one := 1
	two := 2
	objs, err = r.Connection_consoleapi_enmasse_io_v1beta1().Links(context.TODO(), con, nil, &one, nil,  nil)
	assert.NoError(t, err)
	assert.Equal(t, 4, objs.Total, "Unexpected number of links")
	assert.Equal(t, 3, len(objs.Links), "Unexpected number of links in page")

	objs, err = r.Connection_consoleapi_enmasse_io_v1beta1().Links(context.TODO(), con, &one, &two, nil,  nil)
	assert.NoError(t, err)
	assert.Equal(t, 4, objs.Total, "Unexpected number of links")
	assert.Equal(t, 1, len(objs.Links), "Unexpected number of links in page")
}


func TestQueryConnectionMetrics(t *testing.T) {
	r := newTestConnectionResolver(t)
	con1 := uuid.New().String()
	con2 := uuid.New().String()
	namespace := "mynamespace"
	addressspace := "myaddressspace"

	createMetric := func(namespace string, con string, metricName string, metricValue float64) *consolegraphql.Metric {
		return &consolegraphql.Metric{
			Kind:         "Connection",
			Namespace:    namespace,
			AddressSpace: addressspace,
			Name:         con,
			Value:        consolegraphql.NewSimpleMetricValue(metricName, "gauge", float64(metricValue), "", time2.Now()),
		}
	}

	err := r.Cache.Add(
		createConnectionLink(namespace, addressspace, con1, "sender"),
		createConnectionLink(namespace, addressspace, con1, "sender"),
		createConnectionLink(namespace, addressspace, con1, "receiver"),
		createConnectionLink(namespace, addressspace, con2, "receiver"))
	assert.NoError(t, err)

	err = r.MetricCache.Add(createMetric(namespace, con1, "enmasse_messages_in", float64(10)), createMetric(namespace, con1, "enmasse_messages_out", float64(20)))
	assert.NoError(t, err)

	con := &ConnectionConsoleapiEnmasseIoV1beta1{
		ObjectMeta: &metav1.ObjectMeta{
			Name:      con1,
			UID:       types.UID(con1),
			Namespace: namespace,
		},
		Spec: &consolegraphql.ConnectionSpec{
			AddressSpace: addressspace,
		},
	}
	objs, err := r.Connection_consoleapi_enmasse_io_v1beta1().Metrics(context.TODO(), con)
	assert.NoError(t, err)

	expected := 4
	actual := len(objs)
	assert.Equal(t, expected, actual, "Unexpected number of metrics")

	sendersMetric := getMetric("enmasse_senders", objs)
	assert.NotNil(t, sendersMetric, "Senders metric is absent")
	value, _, _ := sendersMetric.Value.GetValue()
	assert.Equal(t, float64(2), value, "Unexpected senders metric value")
	receiversMetric := getMetric("enmasse_receivers", objs)
	assert.NotNil(t, receiversMetric, "Receivers metric is absent")
	value, _, _ = receiversMetric.Value.GetValue()
	assert.Equal(t, float64(1), value, "Unexpected receivers metric value")
	messagesInMetric := getMetric("enmasse_messages_in", objs)
	assert.NotNil(t, messagesInMetric, "Messages In metric is absent")
	value, _, _ = messagesInMetric.Value.GetValue()
	assert.Equal(t, float64(10), value, "Unexpected messages in metric value")
	messagesOutMetric := getMetric("enmasse_messages_out", objs)
	assert.NotNil(t, messagesOutMetric, "Messages In metric is absent")
	value, _, _ = messagesOutMetric.Value.GetValue()
	assert.Equal(t, float64(20), value, "Unexpected messages out metric value")
}

func createConnectionLink(namespace string, addressspace string, con string, role string) *consolegraphql.Link {
	linkuid := uuid.New().String()
	return &consolegraphql.Link{
		TypeMeta: metav1.TypeMeta{
			Kind: "Link",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      linkuid,
			UID:       types.UID(linkuid),
			Namespace: namespace,
		},
		Spec: consolegraphql.LinkSpec{
			Connection:   con,
			AddressSpace: addressspace,
			Role:         role,
		},
	}
}