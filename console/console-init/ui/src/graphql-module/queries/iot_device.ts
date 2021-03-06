/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

import gql from "graphql-tag";
import {
  IDeviceFilter,
  getInitialFilter,
  ISortByWrapper
} from "modules/iot-device";

const RETURN_IOT_DEVICE_DETAIL = (
  iotproject: string,
  namespace: string,
  deviceId: string,
  queryResolver?: string
) => {
  const defaultQueryResolver = `
        total
        devices{
          deviceId
          registration{
            enabled
            via
            viaGroups
            ext
            memberOf
            defaults
          } 
          status{
            lastSeen
            updated
            created
          }
          credentials 
        }`;

  if (!queryResolver) {
    queryResolver = defaultQueryResolver;
  }

  const IOT_DEVICE_DETAIL = gql(
    `query iot_device_detail{
         devices(
             iotproject: { name : "${iotproject}", namespace : "${namespace}"},
             filter: "\`$.deviceId\` = '${deviceId}'"){
              ${queryResolver}
          }
     }`
  );
  return IOT_DEVICE_DETAIL;
};

const FILTER_IOT_CREDENTIALS = (
  property?: string,
  filterType?: string,
  filterValue?: string | boolean
) => {
  let filter = "";
  if (filterValue && property?.toLowerCase() === "enabled") {
    filter += "`$." + [property] + "` = " + filterValue + "";
  } else if (
    filterValue &&
    property?.toLowerCase() === "auth-id" &&
    filterType
  ) {
    filter +=
      "`$.type` = '" +
      filterType +
      "' AND `$['auth-id']` = '" +
      filterValue +
      "'";
  } else if (filterValue && property) {
    filter += "`$." + [property] + "` = '" + filterValue + "'";
  }

  return filter;
};

const RETURN_IOT_CREDENTIALS = (
  iotproject: string,
  namespace: string,
  deviceId: string,
  property?: string,
  filterType?: string,
  filterValue?: string | boolean
) => {
  const filter = FILTER_IOT_CREDENTIALS(property, filterType, filterValue);

  const IOT_CREDENTIALS = gql(
    `query iot_credentials{
      credentials(
        filter:"${filter}",
        iotproject:{ name : "${iotproject}", namespace : "${namespace}"},
        deviceId: "${deviceId}"
      ) {
        total   
         credentials
        }
    }`
  );
  return IOT_CREDENTIALS;
};

const DELETE_IOT_DEVICE = gql(
  `mutation delete_iot_device($iotproject: ObjectMeta_v1_Input!, $deviceId: [String!]!) {
    deleteIotDevices(iotproject: $iotproject, deviceIds: $deviceId)
  }`
);

const DELETE_CREDENTIALS_FOR_IOT_DEVICE = gql(
  `mutation delete_credentials_for_device(
    $iotproject: ObjectMeta_v1_Input!
    $deviceId: String!
  ) {
    deleteCredentialsForDevice(iotproject: $iotproject, deviceId: $deviceId)
  }`
);

const SORT_RETURN_ALL_DEVICES_FOR_IOT_PROJECT = (sortBy?: ISortByWrapper) => {
  let orderBy = "";
  if (sortBy) {
    const { property, direction } = sortBy;
    switch (property?.toLowerCase()) {
      case "device-id":
        orderBy = "`$.deviceId` ";
        break;
      case "status":
        orderBy = "`$.enabled` ";
        break;
      case "connection-type":
        break;
      case "last-seen":
        orderBy = "`$.status.lastSeen` ";
        break;
      case "last-updated":
        orderBy = "`$.status.updated` ";
        break;
      case "added-date":
        orderBy = "`$.status.created` ";
        break;
      default:
        break;
    }

    if (orderBy !== "" && direction) {
      orderBy += direction;
    }
  }
  return orderBy;
};

const concatAND = (filter: string) => {
  if (filter?.trim() !== "") return " AND ";
  return "";
};

const FILTER_RETURN_ALL_DEVICES_FOR_IOT_PROJECT = (
  filterObject: IDeviceFilter
) => {
  const { deviceId, status, deviceType } = filterObject;
  let filter: string = "";

  if (deviceId && deviceId.trim() !== "") {
    filter += "`$.deviceId` = '" + deviceId + "'";
  }

  if (status?.trim() !== "" && status !== "allStatus") {
    filter += concatAND(filter);
    switch (status?.trim()) {
      case "disabled":
        filter += "`$.enabled`=false";
        break;
      case "enabled":
        filter += "`$.enabled`=true";
        break;
    }
  }

  if (deviceType?.trim() !== "" && deviceType?.trim() !== "allTypes") {
    filter += concatAND(filter);
    switch (deviceType?.trim()) {
      case "gateway":
        filter += "`$.viaGateway`=true";
        break;
      case "direct":
        filter += "`$.viaGateway`=false";
        break;
    }
  }

  // TODO: Needs to handle more parameters once the mock supports it.

  return filter;
};

const RETURN_ALL_DEVICES_FOR_IOT_PROJECT = (
  page: number,
  perPage: number,
  projectName: string,
  namespace: string,
  sortBy?: ISortByWrapper,
  filterObj?: IDeviceFilter,
  queryResolver?: string
) => {
  const defaultQueryResolver = `
    total
    devices {
      deviceId
      registration{
        enabled
        via
        memberOf
        viaGroups
      }
      status{
          lastSeen
          updated
          created
        }
      credentials
    }
  `;

  if (!queryResolver) {
    queryResolver = defaultQueryResolver;
  }

  let filter = FILTER_RETURN_ALL_DEVICES_FOR_IOT_PROJECT(
    filterObj || getInitialFilter()
  );

  let orderBy = SORT_RETURN_ALL_DEVICES_FOR_IOT_PROJECT(sortBy);

  const ALL_DEVICE_LIST = gql(
    `query devices_for_iot_project {
      devices(iotproject: { name : "${projectName}", namespace : "${namespace}"},first:${perPage}, offset:${perPage *
      (page - 1)}, orderBy:"${orderBy}", filter: "${filter}") {
        ${queryResolver}
      }
    }`
  );

  return ALL_DEVICE_LIST;
};

const TOGGLE_IOT_DEVICE_STATUS = gql(
  `mutation toggle_iot_devices_status($a: ObjectMeta_v1_Input!, $b: [String!]!, $status: Boolean!){
    toggleIoTDevicesStatus(iotproject: $a, devices: $b, status: $status)
  }`
);

const CREATE_IOT_DEVICE = gql`
  mutation createIotDevice(
    $iotproject: ObjectMeta_v1_Input!
    $device: Device_iot_console_input!
  ) {
    createIotDevice(iotproject: $iotproject, device: $device) {
      deviceId
    }
  }
`;

const SET_IOT_CREDENTIAL_FOR_DEVICE = gql(
  `mutation set_iot_credential_for_device(
    $iotproject: ObjectMeta_v1_Input!
    $deviceId: String!
    $jsonData: String!
  ){
    setCredentialsForDevice(
      iotproject:$iotproject,
      deviceId:$deviceId,
      jsonData:$jsonData
    )
  }
  `
);

const UPDATE_IOT_DEVICE = gql`
  mutation updateIotDevice(
    $iotproject: ObjectMeta_v1_Input!
    $device: Device_iot_console_input!
  ) {
    updateIotDevice(iotproject: $iotproject, device: $device) {
      deviceId
    }
  }
`;

export {
  RETURN_IOT_DEVICE_DETAIL,
  RETURN_IOT_CREDENTIALS,
  DELETE_IOT_DEVICE,
  RETURN_ALL_DEVICES_FOR_IOT_PROJECT,
  DELETE_CREDENTIALS_FOR_IOT_DEVICE,
  TOGGLE_IOT_DEVICE_STATUS,
  CREATE_IOT_DEVICE,
  SET_IOT_CREDENTIAL_FOR_DEVICE,
  UPDATE_IOT_DEVICE
};
