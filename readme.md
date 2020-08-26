# Canary Deployment on Google Kubernetes Engine 

This repository holds scripts demonstrating how to use Google Cloud Build as a Continuous Deployment 
system to deploy a SpringBoot application to GKE.

## Architecture

![](./media/arch.png)

#### Cloud Build

Cloud Build is a service which executes your builds directly on the GCP platform, using a build config file. The build 
is executed as a series of steps, with each step running in its own Docker container. Google has published a set of common 
[open-source build steps](https://github.com/googlecloudplatform/cloud-builders) for common laguages, has a range of 
[community-contributed steps](https://github.com/googlecloudplatform/cloud-builders-community) and also has functinality for 
you to [create your own](https://cloud.google.com/cloud-build/docs/configuring-builds/use-community-and-custom-builders) ones.

#### Cloud Build Triggers

Cloud Build Triggers are set up to watch a source code repository and automatically start a build whenever a change is 
pushed to the source code. In this example we use GitHub, but the porcess is the same for BitBucket and Google Cloud 
Source Repositories. The trigger can be configured to build your code on any detected change or when a change meets 
certain criteria. 

In this example we are configuring 3 triggers to act as follows:

- Push to master --> cloudbuild-canary.yaml --> deploy 1 pod to Production namespace
- Push a tag --> cloudbuild-production.yaml --> deploy 7 pods to Production namespace
- Push to a branch --> cloudbuild-dev.yaml --> deploy 1 pod to a new namespace

To set up CD follow these commands from the gcp cloud shell:

## Setting it up 

### Configure GCP environment

```
    export PROJECT=$(gcloud info --format='value(config.project)')
    export CLUSTER=gke-deploy-cluster
    export ZONE=europe-west1-b

    gcloud config set compute/zone $ZONE
```

#### Enable Services
```
gcloud services enable container.googleapis.com --async
gcloud services enable containerregistry.googleapis.com --async
gcloud services enable cloudbuild.googleapis.com --async
gcloud services enable sourcerepo.googleapis.com --async
```
#### Create Container Cluster

```
gcloud container clusters create ${CLUSTER} \
--project=${PROJECT} \
--zone=${ZONE} \
--quiet
```

#### Get Credentials

```
gcloud container clusters get-credentials ${CLUSTER} \
--project=${PROJECT} \
--zone=${ZONE}
```

#### Give Cloud Build Rights

For `kubectl` commands against GKE youll need to give Cloud Build Service Account container.developer role access 
on your clusters [details](https://github.com/GoogleCloudPlatform/cloud-builders/tree/master/kubectl).

```
PROJECT_NUMBER="$(gcloud projects describe \
    $(gcloud config get-value core/project -q) --format='get(projectNumber)')"

gcloud projects add-iam-policy-binding ${PROJECT} \
    --member=serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com \
    --role=roles/container.developer

```

### Create repo mapping with Cloud Build & GitHub
1. In the GCP UI navigate to Cloud Build --> Triggers --> Connect Repository.
2. Select Github 
3. Link your GitHub account and point to correct repository.
4. Connect (& Skip the first trigger it creates for you)

### Setup triggers
Cloud Build triggers which watch the source repository ang build the application when the required conditions
are met. Here we use 3 triggers which are stored within the gcp/triggers folder. To deploy them execute the 3 API calls 
bellow within folder gcp/triggers. If doing from Cloud Shell you will need to pull this repo into the shell so that you 
can access the files.

1. Push to a branch - creates a new cluster within the GKE service with the cluster name matching the 
branch name
2. Push to master branch - Creates a canary release
3. Push of a tag to master branch - deploys the code to production namespace in GKE

> *NOTE:* Change the values under github.owner and github.name within all the trigger.json files (in /gcp folder)
```
    curl -X POST \
        https://cloudbuild.googleapis.com/v1/projects/${PROJECT}/triggers \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
        --data-binary @branch-build-trigger.json

    curl -X POST \
        https://cloudbuild.googleapis.com/v1/projects/${PROJECT}/triggers \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
        --data-binary @master-build-trigger.json

    curl -X POST \
        https://cloudbuild.googleapis.com/v1/projects/${PROJECT}/triggers \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
        --data-binary @tag-build-trigger.json
```

Review triggers are setup on the [Build Triggers Page](https://console.cloud.google.com/gcr/triggers) 

#### Build & Deploy of local content (optional)

The following submits a build to Cloud Build and deploys the results to a user's namespace. (Note: username must consist of lower case 
alphanumeric characters or '-', and must start and end with an alphanumeric character (e.g. 'my-name',  or '123-abc', regex used for 
validation is '[a-z0-9]([-a-z0-9]*[a-z0-9])?'))

```
gcloud builds submit \
    --config gcp/builder/cloudbuild-local.yaml \
    --substitutions=_VERSION=[SOME-VERSION],_USER=$(whoami),_CLOUDSDK_COMPUTE_ZONE=${ZONE},_CLOUDSDK_CONTAINER_CLUSTER=${CLUSTER} .
```


