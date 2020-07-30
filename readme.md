# Spring Boot GKE deployment

This repository holds scripts demonstrating how to use Google Cloud Build as a Continuous Deployment 
system to deploy a SpringBoot application to GKE.

To set up CD follow these commands from the gcp cloud shell:

### Setup GCP environment

### Set Variables

```
    export PROJECT=$(gcloud info --format='value(config.project)')
    export CLUSTER=gke-deploy-cluster
    export ZONE=us-central1-a

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

### Edit build files
During the deploy step of the build process the image tag is dynamically changed to point to the newly built image. 
This is done through a `sed` command which looks like this: 

```
sed -i 's|gcr.io/two-tier-app-gke/demo:.*|gcr.io/$PROJECT_ID/demo:${_USER}-${_VERSION}|' ./kubernetes/deployments/dev/*.yaml
```

The `two-tier-app-gke` path here points to the project name. Change this to your projec name in the 4 build files (under /builder directory)


### Setup triggers
Cloud Build triggers watch the source repository ang build the application when the required conditions
are met. Here we use 3 triggers which are stored within the gcp/triggers folder. Use the scripts below 
to deploy them. 

1. Push to a branch - creates a new cluster within the GKE service with the cluster name matching the 
branch name
2. Push to master branch - Creates a canary release
3. Push of a tag to master branch - deploys the code to production namespace in GKE

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

### Create Databse
This demo uses a postgreSQL database running on Cloud SQL which you have to deploy. Once the app starts, 
flyway will do the rest (create tabe + populate some data).

Set up database: 
```
gcloud sql instances create <DATABASE-NAME> --tier=db-n1-standard-1 --region=us-central1

gcloud sql users set-password root --host=% --instance <DATABASE-NAME> --password <PASSWORD>

gcloud sql databases create <TABLE-NAME> --instance=<DATABSE-NAME>
```
Get your database connection name:

```
gcloud sql instances describe test-instance-inventory-management | grep connectionName
```

#### Build & Deploy of local content (optional)

The following submits a build to Cloud Build and deploys the results to a user's namespace. (Note: username must consist of lower case 
alphanumeric characters or '-', and must start and end with an alphanumeric character (e.g. 'my-name',  or '123-abc', regex used for 
validation is '[a-z0-9]([-a-z0-9]*[a-z0-9])?'))

```
gcloud builds submit \
    --config gcp/builder/cloudbuild-local.yaml \
    --substitutions=_VERSION=[SOME-VERSION],_USER=$(whoami),_CLOUDSDK_COMPUTE_ZONE=${ZONE},_CLOUDSDK_CONTAINER_CLUSTER=${CLUSTER} .
```

## Deploy Mechanisms

## Deploy Branches to Namespaces

Development branches are a set of environments your developers use to test their code changes before submitting them for integration 
into the live site. These environments are scaled-down versions of your application, but need to be deployed using the same mechanisms 
as the live environment.

### Create a development branch

To create a development environment from a feature branch, you can push the branch to the Git server and let Cloud Build deploy your environment. 

1. Create a development branch and push it to the Git server.

    ```
    git checkout -b new-feature
    ```

2. Modify the source code.
3. Commit and push your changes. This will kick off a build of your development environment.
4. Navigate to the [Build History Page](https://console.cloud.google.com/cloud-build/builds) user interface where you can see that your build started 
for the new branch 
5. Click into the build to review the details of the job
6. Retrieve the external IP for the production services. (It can take several minutes before you see the load balancer external IP address.)
    ```
    kubectl get service demo-backend -n new-feature
    ```
7. Navigate to http://[external-ip]/hello

### Deploy Master to canary

Now that you have verified that your app is running your latest code in the development environment, deploy that code to the canary environment.

1. Merge your feature branch to master

    ```
    git checkout master

    git merge new-feature

    git push gcp master
    ```

2. Navigate to the [Build History Page](https://console.cloud.google.com/gcr/builds) user interface where you can see that your build started for 
the master branch. Click into the build to review the details of the job
3. Once complete, you can check the service URL to ensure that some of the traffic is being served by your new version. You should see about 1 in 5 
requests returning the new version.
    ```
    export FRONTEND_SERVICE_IP=$(kubectl get -o jsonpath="{.status.loadBalancer.ingress[0].ip}" --namespace=production services gceme-frontend)

    while true; do curl http://$FRONTEND_SERVICE_IP/version; sleep 1;  done
    ```

### Deploy Tags to production

Now that your canary release was successful and you haven't heard any customer complaints, you can deploy to the rest of your production fleet. 

1. Tag the master branch 
    ```
    git tag v2.0.0

    git push gcp v2.0.0
    ```

2. Review the job on the the [Build History Page](https://console.cloud.google.com/gcr/builds) user interface where you can see that your build started 
for the v2.0.0 tag. Click into the build to review the details of the job
3. Once complete, you can check the service URL to ensure that all of the traffic is being served by your new version.

    ```
    export FRONTEND_SERVICE_IP=$(kubectl get -o jsonpath="{.status.loadBalancer.ingress[0].ip}" --namespace=production services demo-backend)

    while true; do curl http://$FRONTEND_SERVICE_IP/version; sleep 1;  done
    ```

### Testing 

GET Request to "/hello" -> "Hello World"
GET Request to "/hello/{id}" -> "Hello" + message from database (where id = 1,2 or3)
