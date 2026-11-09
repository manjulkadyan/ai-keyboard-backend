# Deploying kb-api (not run yet)

Sprint 3 builds, tests and runs `kb-api` locally only. These steps are for the first real deploy.
Replace `PROJECT_ID` and `PROJECT_NUMBER` (here and in `cloudrun.yaml`) with the Firebase/GCP project.

## 1. Project and APIs

```sh
gcloud config set project PROJECT_ID
gcloud services enable run.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com \
  firestore.googleapis.com firebaseappcheck.googleapis.com playintegrity.googleapis.com compute.googleapis.com \
  androidpublisher.googleapis.com pubsub.googleapis.com identitytoolkit.googleapis.com
```

## 2. Service account

```sh
gcloud iam service-accounts create kb-api --display-name "kb-api"
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member serviceAccount:kb-api@PROJECT_ID.iam.gserviceaccount.com --role roles/datastore.user
gcloud projects add-iam-policy-binding PROJECT_ID \
  --member serviceAccount:kb-api@PROJECT_ID.iam.gserviceaccount.com --role roles/firebaseauth.admin
```

`roles/firebaseauth.admin` is needed because `POST /v1/account/delete` deletes the Firebase user.

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

Collections: `usage`, `usage_m`, `spend`, `ai_reports` (TTL 90 days), and from Sprint 4 `entitlements`
(keyed by the SHA-256 of the purchase token), `entitlement_uids`, `integrity_verdicts` and `rtdn_seen`
(RTDN dedupe, TTL 7 days):

```sh
gcloud firestore fields ttls update expires_at --collection-group=ai_reports --enable-ttl
gcloud firestore fields ttls update expires_at --collection-group=rtdn_seen --enable-ttl
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

## 9. Play billing (Sprint 4)

### Play Developer API access

1. In Play Console, open Users and permissions.
2. Invite `kb-api@PROJECT_ID.iam.gserviceaccount.com`.
3. Give it "View financial data" and "Manage orders and subscriptions" for the app.

kb-api uses the Cloud Run service account through Application Default Credentials. No JSON key is created.

### Entitlement signing key

The client pins the public key (Task 2). Generate the pair on a trusted machine and never commit the private key.

```sh
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out entitlement.pk8.pem
openssl ec -in entitlement.pk8.pem -pubout -out entitlement.pub.pem
gcloud secrets create entitlement-signing-key --data-file=entitlement.pk8.pem
gcloud secrets add-iam-policy-binding entitlement-signing-key \
  --member serviceAccount:kb-api@PROJECT_ID.iam.gserviceaccount.com --role roles/secretmanager.secretAccessor
shred -u entitlement.pk8.pem
```

Put the content of `entitlement.pub.pem` into the Android build configuration. If the key is not set,
`/v1/entitlement*` answers 503 `unavailable`.

### RTDN (Real-time Developer Notifications)

```sh
gcloud pubsub topics create play-rtdn
gcloud pubsub topics add-iam-policy-binding play-rtdn \
  --member serviceAccount:google-play-developer-notifications@system.gserviceaccount.com \
  --role roles/pubsub.publisher
gcloud iam service-accounts create rtdn-push --display-name "RTDN push"
gcloud pubsub subscriptions create play-rtdn-push --topic play-rtdn \
  --push-endpoint https://DOMAIN/internal/rtdn \
  --push-auth-service-account rtdn-push@PROJECT_ID.iam.gserviceaccount.com \
  --push-auth-token-audience https://DOMAIN/internal/rtdn \
  --ack-deadline 30 --min-retry-delay 10s --max-retry-delay 600s
```

1. In Play Console, open Monetization setup.
2. Set the topic to `projects/PROJECT_ID/topics/play-rtdn`.
3. Send a test notification. kb-api answers 204 and does nothing else.

Set `RTDN_AUDIENCE` and `RTDN_SERVICE_ACCOUNT` in `cloudrun.yaml` to the same values. If they are not set,
`/internal/rtdn` answers 401 to every push.

### Data deletion page

`GET /delete-account` is the public page for the Play Data safety form. Set `DELETION_CONTACT_EMAIL`.
The Play Console URL is `https://DOMAIN/delete-account`.
