BASE_COMPOSE := pantera-main/docker-compose/docker-compose.yaml
DEV_COMPOSE := docker-compose.dev.yaml
ENV_FILE := .env.dev
DC := docker compose -f $(BASE_COMPOSE) -f $(DEV_COMPOSE) --env-file $(ENV_FILE)

.PHONY: up down logs rebuild ps

up:
	$(DC) up --build -d

down:
	$(DC) down

logs:
	$(DC) logs -f

rebuild:
	$(DC) build --no-cache
	$(DC) up -d

ps:
	$(DC) ps
