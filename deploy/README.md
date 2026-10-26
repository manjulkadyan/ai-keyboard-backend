# Deploying kb-api (not run in Sprint 3)

Sprint 3 builds, tests and runs `kb-api` locally only. These steps are for the first real deploy.
Replace `PROJECT_ID` and `PROJECT_NUMBER` (here and in `cloudrun.yaml`) with the Firebase/GCP project.

## 1. Project and APIs

```sh
gcloud config set project PROJECT_ID
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com \
  firestore.googleapis.com firebaseappcheck.googleapis.com playintegrity.googleapis.com compute.googleapis.com
```

## 2. Service account

```sh
gcloud iam service-accounts create kb-api --display-name "kb-api"
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member serviceAccount:kb-api@PROJECT_ID.iam.gserviceaccount.com --role roles/datastore.user
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member serviceAccount:kb-api@PROJECT_ID.iam.gserviceaccount.com --role roles/firebaseauth.viewer
```

## 3. Secret Manager (Anthropic key)

The key never goes in the repo, the image, Remote Config or the APK (TRD 5.4). Rotate every 90 days.

```sh
printf '%s' "$ANTHROPIC_API_KEY" | gcloud secrets create anthropic-api-key --data-file=-
gcloud secrets add-iam-policy-binding anthropic-api-key \
  --member serviceAccount:kb-api@PROJECT_ID.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor
```

## 4. Firestore

```sh
gcloud firestore databases create --location=asia-south1 --type=firestore-native
```

Collections used from Task 6 on: `usage`, `usage_m`, `spend`, `entitlements`, `integrity_verdicts`, `ai_reports`
(reports carry a TTL policy of 90 days):

```sh
gcloud firestore fields ttls update expires_at --collection-group=ai_reports --enable-ttl
```

## 5. Build and deploy

```sh
gcloud artifacts repositories create kb-api --repository-format=docker --location=asia-south1
gcloud builds submit --tag asia-south1-docker.pkg.dev/PROJECT_ID/kb-api/kb-api:latest
gcloud run services replace deploy/cloudrun.yaml --region asia-south1
```

`AUTH_MODE=dev` is refused at startup on Cloud Run (`K_SERVICE` is set), so a misconfigured deploy fails closed.

## 6. Load balancer and Cloud Armor (per-IP 60/min)

Put the service behind an external HTTPS load balancer with a serverless NEG, then:

```sh
gcloud compute security-policies create kb-api-armor
gcloud compute security-policies rules create 1000 --security-policy kb-api-armor \
  --expression "true" --action rate-based-ban \
  --rate-limit-threshold-count 60 --rate-limit-threshold-interval-sec 60 \
  --conform-action allow --exceed-action deny-429 --enforce-on-key IP --ban-duration-sec 60
gcloud compute backend-services update kb-api-backend --global --security-policy kb-api-armor
```

Restrict Cloud Run ingress to the load balancer:
`gcloud run services update kb-api --region asia-south1 --ingress internal-and-cloud-load-balancing`.

## 7. App Check and Play Integrity

- Firebase console: register the Android app, enable App Check with the Play Integrity provider, token TTL 24 h (TRD 11.4).
- Enforce App Check only after the release build is verified end to end (device checklist row).
- Play Console: request a Play Integrity API quota increase now; it is not granted instantly (TRD 11.4).

## 8. Logs

Logs carry only uid hash, route, status, latency, prompt id@version, model and token counts. Add a log-based
exclusion for anything else as defence in depth; request text is never logged by the service.
