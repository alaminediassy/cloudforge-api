# CloudForge API

## Description
CloudForge API est le backend de la plateforme d'ingénierie CloudForge. Il fournit actuellement les services liés à la gestion des identités et des sessions utilisateurs.

## Architecture
Le projet respecte les principes de l'Architecture Hexagonale (Ports et Adaptateurs). Le code est structuré de manière à isoler le domaine métier de l'infrastructure technique et des points d'entrée de l'API.

## Stack technique
- Java 21
- Spring Boot
- Maven
- PostgreSQL
- Redis
- JSON Web Tokens (JJWT)

## Prérequis
Pour exécuter le projet localement, les éléments suivants doivent être installés :
- Un JDK 21
- Docker et Docker Compose

## Installation
Cloner le dépôt :

```bash
git clone https://github.com/alaminediassy/cloudforge-api.git
cd cloudforge-api
```

Créer le fichier de configuration locale :

```bash
cp .env.example .env
```

Démarrer PostgreSQL et Redis :

```bash
docker compose up -d postgres redis
```

## Lancement
Une fois l'infrastructure prête, lancer l'application via le wrapper Maven :

```bash
./mvnw spring-boot:run
```

L'API sera accessible sur le port 8080.
La documentation interactive OpenAPI (Swagger) est disponible à l'adresse :
http://localhost:8080/swagger-ui.html

## Tests
Pour lancer l'ensemble de la suite de tests et générer le rapport de couverture JaCoCo :

```bash
./mvnw clean verify
```

L'exécution des tests nécessite que les conteneurs PostgreSQL et Redis locaux soient en cours de fonctionnement.

## CI/CD et qualité
Le projet utilise GitHub Actions pour automatiser les tests, l'analyse qualité et la construction/publication de l'image Docker. Le pipeline est structuré en trois étapes séquentielles :

1. test : Exécution des tests Maven et génération des rapports de couverture.
2. quality : Analyse du code via SonarCloud et application d'un Quality Gate bloquant.
3. docker : Construction de l'image Docker multi-stage et publication sur GitHub Container Registry (GHCR). Les images sont publiées avec les tags `develop` ou `latest`, ainsi qu'un tag basé sur le SHA du commit.

## Fonctionnalités actuelles
- Inscription utilisateur avec mot de passe haché.
- Vérification d'adresse email via code OTP.
- Authentification par email et mot de passe.
- Gestion des sessions avec Access Token et Refresh Token (JWT).
- Rotation sécurisée des Refresh Tokens.
- Déconnexion avec révocation de session.
