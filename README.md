# 🔗 PBFT Consensus Network (Client-Server Architecture)

A robust, distributed implementation of the **Practical Byzantine Fault Tolerance (PBFT)** consensus algorithm in Java. 

This project simulates a distributed network where nodes must reach a consensus on the state of a system, even in the presence of malicious (Byzantine) actors or node failures. It features a complete Client-Server architecture communicating via RESTful web services (JAX-RS/Jersey).

## 🏗️ System Architecture

The project is divided into three main components:

*   **`cliente/`**: Java client application that acts as the requester. It sends service requests (`PeticionServicio.java`) to the network and awaits the mathematically required number of matching responses (`RespuestaServicio.java`) to consider the request validated.
*   **`servidor/`**: The backend nodes that process the PBFT algorithm. They handle incoming requests, multicast messages to other nodes (Pre-Prepare, Prepare, Commit phases), and manage the internal state machine (`EstadoProceso.java`).
*   **`scripts/` (Deployment & Security):** Automation scripts for network deployment and cryptographic key distribution.

## 🚀 Deployment & Security

Deploying a Byzantine Fault Tolerant network requires strict node authentication. The deployment process is automated using bash scripts:

1.  **Key Distribution (`shareKeys.sh`):** Automates the generation and distribution of cryptographic keys across the network nodes. This ensures that every message broadcasted during the consensus phases (Pre-Prepare, Prepare, Commit) is securely signed and verifiable, preventing spoofing by malicious nodes.
2.  **Network Deployment (`deploy.sh`):** Automates the packaging and deployment of the server nodes to their respective environments, setting up the distributed topology required for the PBFT algorithm to function.

## 🛠️ Tech Stack

*   **Language:** Java
*   **Networking:** Jakarta EE, JAX-RS (Jersey) for RESTful APIs.
*   **Security:** Cryptographic signing for node authentication (PKI).
*   **DevOps/Scripting:** Bash (`.sh`) for deployment automation.

## ⚙️ Requirements

To build and run this project, ensure you have the following dependencies configured (e.g., via Maven/Gradle or in your Java EE server environment):
*   Jersey Server & Client (2.x)
*   Jakarta JSON Processing & Binding
*   Jakarta RESTful Web Services
*   AOP Alliance & HK2 (for dependency injection)
