# Spring Boot GKE deployment

This repository demonstrates how to use Google Cloud Build as a Continuous Deployment system to deploy 
a SpringBoot application to GKE using [Binary Authorization](https://cloud.google.com/binary-authorization/docs) 
to ensure the application image meets necessary requirements.

## Architecture

![](./media/arch.png)

#### How it works
A Google Cloud Build Trigger is set up to monitor code pushes to this branch. When code is pushed to this branch the
trigger activates the /gcp/builder/cloudbuild-dev.yaml. A SonarQube analysis is performed on the code, if it passes 
the built image is marked with an attestation and the image is deployed to Google Kubernetes Engine. If the attestation
is not achieved, deployment is not permitted.

1. Prepare resources for Binary Authorization
    - Create Cryptographic Keys
    - Create Attestor and attach Key
    - Create Policy and attach Attestor
    - Give Cloud Builder service account necessary access rights
2. Create GKE cluster
    - enable Binary Authorization and attach policy
3. Configure Cloud Build  

To set up CD follow these commands from the gcp cloud shell:

# Set up steps

## Prepare environment

#### Configure GCP environment

```bash
PROJECT=$(gcloud info --format='value(config.project)')
CLUSTER=gke-deploy-cluster
ZONE=europe-west1-b
PROJECT_NUMBER=$(gcloud projects list --filter="$PROJECT" --format="value(PROJECT_NUMBER)")


gcloud config set compute/zone $ZONE
```

#### Enable Services
```bash
gcloud services enable container.googleapis.com --async
gcloud services enable containerregistry.googleapis.com --async
gcloud services enable cloudbuild.googleapis.com --async
gcloud services enable sourcerepo.googleapis.com --async
```

## 1) Binary Authorization

We are adding a custom build step for signing and uploading Binary Authorization Attestations.

## Background

Binary Authorization (Binauthz) is a Cloud product that enforces deploy-time constraints on applications. 
Its GKE integration allows users to enforce that containers deployed to a Kubernetes cluster are 
cryptographically attested by a trusted authority.

NOTE: This build step assumes some familiarity with Binary Authorization, including how to set up an 
Binary Authorization enforcement policy with an Attestor on a GKE cluster.

To learn more:

-   [A general introduction to Binary Authorization](https://cloud.google.com/binary-authorization/)
-   [An introductory codelab](https://codelabs.developers.google.com/codelabs/cloud-binauthz-intro/index.html#0)

### Cryptographic Keys

A cryptographic key is required to ensure that only authorised parties can attest a container image.
Using a KMS Key-based Signing recommended. The Public key is attached to the attestor, while the private 
key is used to sign the attestation. 

1.  Create a key ring and a key for asymmetric signing using
    [these instructions](https://cloud.google.com/kms/docs/creating-asymmetric-keys#create_an_asymmetric_signing_keys) 
    making sure you choose Asymmetric Sign as the key purpose when you create the key.


### Attestor

An attestor is a GCP resource that Binary Authorization uses to verify the attestation at container image deploy time.
Mor information can be found in docs [here](https://cloud.google.com/binary-authorization/docs/key-concepts#attestors).

1.  Create an attestor following Googles instructions 
    [here](https://cloud.google.com/binary-authorization/docs/creating-attestors-cli) making sure to look at the 
    [PKIX](https://cloud.google.com/binary-authorization/docs/creating-attestors-cli#pkix-cloud-kms) documentation
    when so you attach the key created from the last step.

> *NOTE:* This step can also be done using the [UI](https://cloud.google.com/binary-authorization/docs/creating-attestors-console)

### Policy

A policy is a set of rules that govern the deployment of one or more container images.

1. Create a policy using instructions [here](https://cloud.google.com/binary-authorization/docs/configuring-policy-cli) 
    and the attestor from the previous step.

You want to set `evaluationMode=REQUIRE_ATTESTATION`, `ATTESTOR=[name-of-attestor-from-above-step]` and 
`enforcementMode=ENFORCED_BLOCK_AND_AUDIT_LOG`.

> *NOTE:* This step can also be done using the [UI](https://cloud.google.com/binary-authorization/docs/configuring-policy-console)

### Access

To use this build step, the Cloud Build service account needs the following IAM roles:

-   Binary Authorization Attestor Viewer
    -   `roles/binaryauthorization.attestorsViewer`
-   Cloud KMS CryptoKey Decrypter (if providing KMS-encrypted PGP key through
    secret environment variable)
    -   `roles/cloudkms.cryptoKeyDecrypter`
-   Cloud KMS CryptoKey Signer/Verifier (if using key in KMS to sign
    attestation)
    -   `roles/cloudkms.signerVerifier`
-   Container Analysis Notes Attacher
    -   `roles/containeranalysis.notes.attacher`

The following commands can be used to add the roles to your project's Cloud Build Service Account:

```bash
# Add Binary Authorization Attestor Viewer role to Cloud Build Service Account
gcloud projects add-iam-policy-binding $PROJECT \
  --member serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com \
  --role roles/binaryauthorization.attestorsViewer

# Add Cloud KMS CryptoKey Signer/Verifier role to Cloud Build Service Account (KMS-based Signing)
gcloud projects add-iam-policy-binding $PROJECT \
  --member serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com \
  --role roles/cloudkms.signerVerifier

# Add Container Analysis Notes Attacher role to Cloud Build Service Account
gcloud projects add-iam-policy-binding $PROJECT \
  --member serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com \
  --role roles/containeranalysis.notes.attacher
```

## 2) Google Kubernetes Engine

#### Create Container Cluster

We are going to create a GKE cluster with Binary Authorization enabled. Creating the cluster
will take a couple of minutes

```bash
# Create cluster
gcloud container clusters create ${CLUSTER} \
--enable-binauthz \
--project=${PROJECT} \
--zone=${ZONE} \
--quiet

# Check cluster is created
gcloud container clusters list \
    --zone ${ZONE}

# Get Credentials
gcloud container clusters get-credentials ${CLUSTER} \
--project=${PROJECT} \
--zone=${ZONE}
```

#### Give Cloud Build Rights to the cluster

For `kubectl` commands against GKE youll need to give Cloud Build Service Account container.developer role access 
on your clusters [details](https://github.com/GoogleCloudPlatform/cloud-builders/tree/master/kubectl).

```
PROJECT_NUMBER="$(gcloud projects describe \
    $(gcloud config get-value core/project -q) --format='get(projectNumber)')"

gcloud projects add-iam-policy-binding ${PROJECT} \
    --member=serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com \
    --role=roles/container.developer

```

## Set up Cloud Build & GitHub

1. In the GCP UI navigate to Cloud Build --> Triggers --> Connect Repository.
2. Select Github 
3. Link your GitHub account and point to correct repository.
4. Connect (& Skip the first trigger it creates for you)

#### Setup triggers
Cloud Build triggers which watch the source repository and build the application when the required conditions
are met. We are going to set up one trigger to monitor the current branch in GitHub. 

> *NOTE:* Change the values under github.owner and github.name within all the trigger.json files (in /gcp folder)
```
cd ./gcp/triggers

curl -X POST \
    https://cloudbuild.googleapis.com/v1/projects/${PROJECT}/triggers \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
    --data-binary @branch-build-trigger.json
```

Review triggers are setup on the [Build Triggers Page](https://console.cloud.google.com/gcr/triggers) 


