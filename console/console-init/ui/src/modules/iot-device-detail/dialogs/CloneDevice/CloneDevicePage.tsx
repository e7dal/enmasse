/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

import React, { useState, useMemo } from "react";
import {
  Wizard,
  WizardFooter,
  WizardContextConsumer,
  Button,
  Breadcrumb,
  BreadcrumbItem,
  PageSection,
  Page,
  PageSectionVariants,
  Divider,
  Title,
  Flex,
  FlexItem,
  GridItem,
  Grid
} from "@patternfly/react-core";
import { DeviceInformation } from "modules/iot-device/dialogs";
import { ConnectionType } from "modules/iot-device/components/ConnectionTypeStep";
import { AddGateways, AddCredential } from "modules/iot-device/components";
import { useHistory, useParams } from "react-router";
import { Link } from "react-router-dom";
import { useBreadcrumb } from "use-patternfly";
import { SwitchWithToggle } from "components";
import {
  ReviewDeviceContainer,
  IDeviceProp
} from "modules/iot-device/containers";

const getInitialDeviceForm = () => {
  const device: IDeviceProp = {
    connectionType: "directly",
    deviceInformation: {},
    gateways: {}
  };
  return device;
};

export default function CloneDevicePage() {
  const history = useHistory();
  // TODO : will be use once we integrate server
  // const [connectionType, setConnectionType] = useState<string>("directly");
  const [addedGateways, setAddedGateways] = useState<string[]>([]);
  const { projectname, namespace, deviceid } = useParams();
  const deviceListRouteLink = `/iot-projects/${namespace}/${projectname}/devices`;
  const previousDeviceLink = `/iot-projects/${namespace}/${projectname}/devices/${deviceid}/device-info`;
  const [device, setDevice] = useState<IDeviceProp>(getInitialDeviceForm());
  const breadcrumb = useMemo(
    () => (
      <Breadcrumb>
        <BreadcrumbItem>
          <Link id="home-link" to={"/"}>
            Home
          </Link>
        </BreadcrumbItem>
        <BreadcrumbItem isActive={true}>{projectname}</BreadcrumbItem>
      </Breadcrumb>
    ),
    [projectname]
  );

  useBreadcrumb(breadcrumb);

  const getGateways = (gateways: string[]) => {
    setAddedGateways(gateways);
  };

  const onCloseDialog = () => {
    history.push(previousDeviceLink);
  };

  const addGateway = {
    name: "Add gateways",
    component: <AddGateways />
  };

  const AddCredentialWrapper = () => (
    <Grid hasGutter>
      <GridItem span={8}>
        <Title size="2xl" headingLevel="h1">
          Add credentials to this new device{" "}
        </Title>
        <br />
      </GridItem>
      <GridItem span={8}>
        <AddCredential />
      </GridItem>
    </Grid>
  );

  const addCredentials = {
    name: "Add credentials",
    component: <AddCredentialWrapper />
  };

  const reviewForm = {
    name: "Review",
    component: (
      <ReviewDeviceContainer
        device={device}
        title={"Verify that the following information is correct before done"}
      />
    )
  };

  const onChangeConnection = (_: boolean, event: any) => {
    const connectionType = event.target.value;
    if (connectionType) {
      setDevice({ ...device, connectionType });
    }
  };

  const handleSave = async () => {
    // Add query to add device
    history.push(deviceListRouteLink);
  };

  const steps = [
    {
      name: "Device information",
      component: <DeviceInformation />
    },
    {
      name: "Connection Type",
      component: (
        <ConnectionType
          connectionType={device.connectionType}
          onChangeConnection={onChangeConnection}
        />
      )
    }
  ];

  if (device.connectionType) {
    if (device.connectionType === "directly") {
      steps.push(addCredentials);
    } else {
      steps.push(addGateway);
    }
    steps.push(reviewForm);
  }

  const handleNextIsEnabled = () => {
    return true;
  };

  const CustomFooter = (
    <WizardFooter>
      <WizardContextConsumer>
        {({ activeStep, onNext, onBack, onClose }) => {
          if (
            activeStep.name === "Device information" ||
            activeStep.name === "Connection Type"
          ) {
            return (
              <>
                <Button
                  id="create-device-page-next-button"
                  variant="primary"
                  type="submit"
                  onClick={onNext}
                  className={
                    activeStep.name === "Connection Type" &&
                    !device.connectionType
                      ? "pf-m-disabled"
                      : ""
                  }
                >
                  Next
                </Button>
                <Button
                  id="create-device-page-back-button"
                  variant="secondary"
                  onClick={onBack}
                  className={
                    activeStep.name === "Device information"
                      ? "pf-m-disabled"
                      : ""
                  }
                >
                  Back
                </Button>
                <Button
                  id="create-device-page-cancel-button"
                  variant="link"
                  onClick={onClose}
                >
                  Cancel
                </Button>
              </>
            );
          }
          if (activeStep.name !== "Review") {
            return (
              <>
                <Button
                  id="create-device-page-next-button"
                  variant="primary"
                  type="submit"
                  onClick={onNext}
                  className={handleNextIsEnabled() ? "" : "pf-m-disabled"}
                >
                  Next
                </Button>
                <Button
                  id="create-device-page-back-button"
                  variant="secondary"
                  onClick={onBack}
                >
                  Back
                </Button>
                <Button
                  id="create-device-page-cancel-button"
                  variant="link"
                  onClick={onClose}
                >
                  Cancel
                </Button>
              </>
            );
          }

          return (
            <>
              <Button
                id="create-device-page-finish-button"
                variant="primary"
                onClick={handleSave}
                type="submit"
              >
                Finish
              </Button>
              <Button
                id="create-device-page-back-button"
                onClick={onBack}
                variant="secondary"
              >
                Back
              </Button>
              <Button
                id="create-device-page-cancel-button"
                variant="link"
                onClick={onClose}
              >
                Cancel
              </Button>
            </>
          );
        }}
      </WizardContextConsumer>
    </WizardFooter>
  );

  return (
    <Page>
      <PageSection variant={PageSectionVariants.light}>
        <Flex>
          <FlexItem>
            <Title size={"2xl"} headingLevel="h1">
              Clone a device
            </Title>
          </FlexItem>
          <FlexItem align={{ default: "alignRight" }}>
            <SwitchWithToggle
              id={"create-device-page-view-json-switchtoggle"}
              labelOff={"View JSON Format"}
              onChange={() => {
                //TODO: Add handler to allow User to view in JSON
              }}
              label={"View Form Format"}
            />
          </FlexItem>
        </Flex>
        <br />
        <Divider />
      </PageSection>
      <Wizard
        id="create-device-page-wizard"
        onClose={onCloseDialog}
        onSave={handleSave}
        footer={CustomFooter}
        steps={steps}
      />
    </Page>
  );
}
