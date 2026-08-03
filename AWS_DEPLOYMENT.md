# Deploying Triagent to AWS (and Tearing It Down Cleanly)

This gets the whole stack running on a single public URL on AWS so you can
demo it live, then removes every billable resource afterward. It targets
someone comfortable with EC2 basics but not the rest of AWS.

## The architecture choice, and why

The whole stack already runs as one `docker compose up`. The simplest,
cheapest, and lowest-risk way to put that on AWS is **one EC2 instance that
runs the exact same `docker compose up --build`** — no rewriting anything.

The alternative — ECS Fargate + RDS + ElastiCache + a managed vector DB +
an Application Load Balancer — is the "how a team would actually run this
in production" version. It's also 5-10x the moving parts, real recurring
cost, IAM roles, VPC subnet planning, and a much bigger surface for
something to silently keep billing after you think you've torn it down.
For a portfolio demo you want to screenshot and then delete, it's not worth
it. Do the EC2 version first; if you want the ECS version later as a
separate resume line, treat it as its own project.

## Cost estimate (why $100 of credit easily covers this)

| Item | Rate | For a ~1 day test | 
|---|---|---|
| `t3.large` on-demand (2 vCPU/8GB) | ~$0.0832/hr | a few hours = well under $1 |
| 30 GB gp3 EBS | ~$0.08/GB-month | prorated to a day = a few cents |
| Public IPv4 address (charged since Feb 2024, even for the auto-assigned one) | $0.005/hr | a few cents |

Total for spinning this up, demoing it, and tearing it down same-day:
**under $1**. The only way to spend real money is to forget to terminate
the instance and leave it running for weeks — which is why Step 0 sets up
a billing alarm as a safety net, and the teardown section at the end is
mandatory reading, not optional.

`t3.micro`/`t3.small` (the free-tier-eligible sizes) are **not** enough
RAM to run all 8 containers plus build the Maven/Node images — you'll hit
OOM. Use `t3.large`; it's cheap enough on-demand that this doesn't matter.

## Step 0: Before you touch EC2

**Set a billing alarm** (do this first, it's your safety net):
- AWS Console → search "Billing" → **Budgets** → **Create budget**
- Template: "Zero spend budget" or a custom cost budget, threshold **$5**
- Enter your email for the alert

**Get your code onto GitHub** (a private repo is fine — `.env` with your
real API key is gitignored, so nothing secret gets pushed):
```bash
cd /Users/gurpreetsingh/Documents/triagent
git remote add origin https://github.com/<you>/triagent.git   # skip if already set
git push -u origin master
```

Pick one AWS region and stick with it for every step below (e.g.
`us-east-1` / N. Virginia — cheapest, most services available). The region
selector is the dropdown in the top-right of the console.

## Step 1: Security group (the firewall)

Console → **EC2** → **Security Groups** (left sidebar, under "Network &
Security") → **Create security group**

- Name: `triagent-sg`
- Inbound rules → **Add rule** for each:
  | Type | Port | Source |
  |---|---|---|
  | SSH | 22 | My IP |
  | Custom TCP | 5173 | My IP (frontend) |
  | Custom TCP | 8080 | My IP (backend API, for curl testing) |
  | Custom TCP | 3000 | My IP (Grafana) |
  | Custom TCP | 9090 | My IP (Prometheus, optional) |
- Outbound: leave the default "all traffic allowed" — the backend needs to
  reach the OpenAI API.

Using "My IP" instead of "Anywhere" means only your current IP can reach
it. If you want to share the live link with someone else temporarily,
you can edit the rule to "Anywhere" (0.0.0.0/0) for the demo window and
revert it after — just know that opens the ports to the whole internet.

## Step 2: Key pair

Console → **EC2** → **Key Pairs** → **Create key pair**
- Name: `triagent-key`, type: ED25519, format: `.pem`
- Downloads automatically. Then:
```bash
chmod 400 ~/Downloads/triagent-key.pem
```

## Step 3: Launch the instance

Console → **EC2** → **Instances** → **Launch instances**
- Name: `triagent-demo`
- AMI: **Ubuntu Server 22.04 LTS**
- Instance type: **t3.large**
- Key pair: `triagent-key`
- Network settings → Edit → select existing security group `triagent-sg`,
  make sure "Auto-assign public IP" is **Enable**
- Storage: change the root volume from the default 8 GiB to **30 GiB**
  (gp3) — the default is too small once Docker images/build cache pile up
- **Launch instance**

Wait ~1 minute, then note its **Public IPv4 address** from the instance
list (or click into the instance).

## Step 4: Connect and install Docker

```bash
ssh -i ~/Downloads/triagent-key.pem ubuntu@<public-ip>
```

On the instance:
```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg git python3-venv python3-pip

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo usermod -aG docker $USER
newgrp docker    # or exit and ssh back in

docker compose version   # sanity check
```

## Step 5: Get the code and secrets onto the instance

```bash
git clone https://github.com/<you>/triagent.git
cd triagent
cp .env.example .env
nano .env    # paste in your real OPENAI_API_KEY, save (Ctrl+O, Enter, Ctrl+X)
```

## Step 6: Bring the stack up

`docker-compose.yml` alone keeps Postgres/Redis/Qdrant/agent-service internal-only
(`expose:`, not `ports:`) — the ingestion step below needs Qdrant reachable from the
instance's own host, so bring the stack up with the dev override:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose ps    # wait until all 8 services show "healthy" (builds take a few min on first run)
```

This is still safe on a public box: the security group from Step 1 only allows inbound
on 22/5173/8080/3000/9090, so the extra ports the dev override publishes (5432, 6379,
6333, 8000) are unreachable from the internet regardless — they're only reachable from
the instance's own localhost, which is what ingestion needs. If you want the fully
hardened config (no dev override at all), skip this and instead run ingestion from a
one-off container attached to `triagent-net` instead of from the instance's host.

## Step 7: One-time doc corpus ingestion

Same as the local README, run from the instance:
```bash
cd ingestion
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/python ingest.py --recreate
./.venv/bin/python smoke_query.py "why would payment service duplicate a charge"
cd ..
```

## Step 8: See it live

From your own laptop's browser (not the SSH session):
- Dashboard: `http://<public-ip>:5173`
- Grafana: `http://<public-ip>:3000` (admin/admin)
- Prometheus: `http://<public-ip>:9090`

Trigger an incident from your laptop to prove it's really live on AWS:
```bash
curl -X POST http://<public-ip>:8080/api/webhooks/pagerduty -H "Content-Type: application/json" -d '{
  "routing_key": "R123", "event_action": "trigger", "dedup_key": "aws-demo-1",
  "payload": {"summary": "payment-service returning elevated 5xx errors, error rate 12%",
  "source": "payment-service-prod-1", "severity": "critical", "timestamp": "2026-01-01T00:00:00Z",
  "component": "payment-service", "group": "payments", "class": "5xx-spike",
  "custom_details": {"error_rate": "0.12"}}, "client": "curl", "client_url": "http://localhost"
}'
```

Then refresh the dashboard at `http://<public-ip>:5173` and watch the
ticket appear. This is the moment to take your screenshots/screen
recording for the portfolio.

Optionally run the full eval harness against the live deployment (from the
instance, or from your laptop with `--backend-url http://<public-ip>:8080`)
— see [TESTING.md](./TESTING.md) section 7.

## Step 9: TEAR EVERYTHING DOWN

Do this as soon as you're done demoing — this is the step that actually
stops the billing.

1. **Terminate the instance** (not just "stop" — stopping still holds the
   EBS volume and, in some cases, the public IP; terminating releases
   everything cleanly):
   Console → EC2 → Instances → select `triagent-demo` → **Instance state**
   → **Terminate instance** → confirm.

2. **Confirm the EBS volume is gone.** The root volume is set to
   delete-on-termination by default, but double check:
   Console → EC2 → **Volumes** (left sidebar) → make sure nothing is
   listed as `available`/orphaned from this instance. Delete manually if
   one lingers.

3. **Check for Elastic IPs.** This guide never allocated one (we used the
   free auto-assigned public IP), but confirm:
   Console → EC2 → **Elastic IPs** → should show none. An Elastic IP not
   attached to a running instance bills continuously even though it looks
   idle — this is the #1 way people get a surprise AWS bill, so it's worth
   the 10-second check even though this guide doesn't create one.

4. **Delete the security group** (no cost, just cleanup): Console → EC2 →
   Security Groups → select `triagent-sg` → Delete (only possible after
   the instance is terminated).

5. **Delete the key pair** (no cost, just cleanup): Console → EC2 → Key
   Pairs → select `triagent-key` → Delete.

6. **Verify in Billing.** Console → search "Billing" → **Bills** (or
   **Cost Explorer**). AWS usage typically posts within a few hours to a
   day — check back the next day and confirm the total is near $0.

After step 1, nothing is running and nothing is billing per-hour; steps
2-6 are cleanup/verification so nothing is silently left behind.
