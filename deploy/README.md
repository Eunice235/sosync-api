# Deploying SOSync to Oracle Cloud

Three steps in Oracle's website, then one command from your laptop. Nothing is installed on the
laptop: `ssh` and `scp` are built into Windows, and everything else is installed on the server.

## 1. Create the server (Oracle console)

**Compute → Instances → Create instance**

| Setting | Choose |
|---|---|
| Image | **Canonical Ubuntu 24.04** |
| Shape | **Ampere → VM.Standard.A1.Flex**, 2 OCPU, 12 GB memory (inside the free allowance) |
| Networking | Leave the defaults; make sure **Assign a public IPv4 address** is on |
| SSH keys | **Generate a key pair for me → Save private key**. Keep that file. |

If Oracle says *"Out of host capacity"*, try another availability domain. The fallback is
**VM.Standard.E2.1.Micro** (AMD). It works, but with 1 GB of memory it is slow to start.

When it shows **Running**, copy its **Public IP address**.

## 2. Open port 80 (Oracle console)

**Networking → Virtual cloud networks → (your VCN) → Security Lists → Default Security List →
Add Ingress Rules**

| Field | Value |
|---|---|
| Source CIDR | `0.0.0.0/0` |
| IP Protocol | TCP |
| Destination Port Range | `80` |

The server has a second firewall inside the VM itself. `setup.sh` opens that one for you.

## 3. Deploy (your laptop)

Double-click **`deploy\deploy.bat`**. It asks for:
- the server's public IP
- the private key file from step 1 — you can drag the file into the window

It builds the backend, uploads it, installs Docker on the server, generates secrets, starts the
API and the database, and then checks the API can be reached from the internet. It finishes by
printing the address, e.g. `http://129.146.12.34`.

**To update after changing backend code, run `deploy.bat` again.** It remembers the IP and key,
keeps the database and secrets, and only rebuilds and restarts the API.

## 4. Point the app at the server

In `mobile/lib/config.dart`:

```dart
static const String serverAddress = 'http://YOUR-SERVER-IP';
```

Then build the APK to share:

```bash
cd mobile && flutter build apk --release
```

The file is `mobile/build/app/outputs/flutter-apk/app-release.apk`. Send it over WhatsApp,
email or Drive. People installing it need to allow **Install unknown apps** for whichever app
they open it from, and may see a Play Protect warning — **Install anyway**. That warning is
normal for apps shared outside the Play Store.

If someone installed an earlier build, they should uninstall it first.

## If something goes wrong

| What you see | What it means |
|---|---|
| *Could not connect over SSH* | Wrong IP, the VM is stopped, or not the key from step 1 |
| *Running on the server but the internet cannot reach it* | Step 2 was missed. Add the rule; no redeploy needed |
| *The API did not start within three minutes* | On the server: `cd ~/sosync && sudo docker compose logs api` |
| App says *server did not answer in time* | `serverAddress` in `config.dart` does not match the server IP, or the APK was built before you changed it |

## What is on the server

```
~/sosync/
├── app.jar              the backend, built on your laptop
├── Dockerfile           runs it with Java 21
├── docker-compose.yml   the API and Postgres
├── setup.sh             firewall, Docker, secrets, start
└── .env                 generated secrets - do not delete; the database uses its password
```

The database listens only inside the server and cannot be reached from the internet. The API is
served on port 80 over plain HTTP. That is fine for testing with people you know, but logins and
live locations travel unencrypted, so HTTPS belongs on the list before this is used for real.

**The demo accounts' shared password is published in the project README.** Anyone who reads it
can sign in as the demo administrator and see every incident, including where your testers
were. For a short test with people you know, that risk is small. If your repo is going public
for submission, change the demo passwords first.
