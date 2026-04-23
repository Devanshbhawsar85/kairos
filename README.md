# 📖 Kairos — AI-Powered Auto-Scaling System

## 🚀 Overview

**Kairos** (Greek for *“the right, critical moment”*) is an intelligent auto-scaling system that dynamically scales Docker containers based on real-time CPU and memory metrics using AI-powered decision-making with a **RAG (Retrieval-Augmented Generation)** architecture.

---

## 🎯 Features

* 🤖 **AI-Driven Scaling** — Uses Groq LLM (LLaMA 70B) for decisions
* 🧠 **Semantic Memory** — Stores historical decisions as embeddings (pgvector)
* 🔍 **RAG Architecture** — Context-aware scaling using past data
* ⚡ **Event-Driven** — Microservices communicate via Kafka + Zookeeper
* 🛡️ **Graceful Fallback** — Rule-based scaling when AI fails
* 📊 **Real-Time Monitoring** — Prometheus + Grafana dashboards
* 🔔 **Alerts** — Slack notifications for scaling events
* 🧪 **Load Tested** — k6 (100 users, 300 req/sec peak)

---

## 🏗️ Architecture

```
Main App → Auto-Scaler → Kafka → Workers → Monitoring + Alerts
```

**Microservices:**

| Service             | Port | Role               |
| ------------------- | ---- | ------------------ |
| Auto-Scaler         | 8080 | Decision engine    |
| Remediation Worker  | 8084 | Executes scaling   |
| Analytics Worker    | 8083 | Stores metrics     |
| Notification Worker | 8082 | Sends alerts       |
| Main App            | 8081 | Target application |

---

## 🧠 AI Workflow

1. Collect CPU & Memory metrics
2. Generate embeddings (Jina AI)
3. Store in pgvector
4. Retrieve similar past cases
5. Send context to Groq LLM
6. Get decision (SCALE_UP / DOWN / etc.)
7. Publish to Kafka
8. Execute + store + notify

---

## 📊 Scaling Rules (Fallback)

| Condition                | Action     |
| ------------------------ | ---------- |
| CPU > 70%                | SCALE_UP   |
| Memory > 80%             | SCALE_UP   |
| CPU < 25% & Memory < 40% | SCALE_DOWN |
| Container down           | RESTART    |

---

## 🛠️ Tech Stack

* **Java 17 + Spring Boot**
* **Groq LLM + Jina AI**
* **PostgreSQL + pgvector**
* **Kafka + Zookeeper**
* **Docker**
* **Prometheus + Grafana**
* **Slack Webhooks**

---

## 🚦 Prerequisites

* Docker & Docker Compose
* Java 17
* PostgreSQL (with pgvector)
* Kafka + Zookeeper

---

## 🔧 Setup

### 1. Clone Repo

```bash
git clone https://github.com/your-repo/kairos.git
cd kairos
```

### 2. Environment Variables

Create `.env` (DO NOT COMMIT THIS FILE):

```env
GROQ_API_KEY=your_key
JINA_API_KEY=your_key
SLACK_WEBHOOK_URL=your_url
```

### 3. Start Services

```bash
docker-compose up -d --build
docker ps
```

---

## 🧪 Testing

```bash
k6 run load-test.js
```

---

## 📈 Monitoring

* Grafana: http://localhost:3000
* Prometheus: http://localhost:9090

---

## 🐛 Troubleshooting

* Docker issues → check socket
* Kafka issues → restart container
* pgvector → ensure extension enabled

---

## 📊 Key Metrics

* 100 concurrent users
* 300 req/sec peak
* 75% AI decision coverage
* 768-dim embeddings

---

## 🤝 Contributing

1. Fork repo
2. Create branch
3. Commit changes
4. Push & open PR

---

## 📧 Contact

**Devansh Bhawsar**
GitHub: https://github.com/Devanshbhawsar85

---

⭐ If you found this useful, give it a star!

Built with ☕ and AI
