# tech-challenge-billing-service
Tech Challenge Fase 4 - Microsserviço de Orçamento e Pagamento | Gerencia orçamentos, pagamentos via Mercado Pago e atualização de status | Arquitetura de Microsserviços com PostgreSQL, integração de pagamentos e Kubernetes

Serviço com banco PostgreSQL próprio, API REST e publicação em RabbitMQ. Não acessa o banco do OS Service. O código inicial do OS Service foi consultado para o contrato REST e os estados existentes.

## Situação da integração e limitações

**Este repositório, isoladamente, não entrega a saga distribuída completa exigida pelo desafio.** No OS Service consultado, `SagaOrchestrator` é um placeholder, a publicação de eventos pode falhar sem persistência e não há consumidor de eventos do Billing. O serviço de execução e seus contratos não estão disponíveis aqui. O BDD deste repositório usa substitutos para OS e Mercado Pago; não prova o fluxo integrado entre os três serviços.

- A API registra um orçamento por OS, retorna seus dados para que outro sistema os apresente ao cliente e registra a decisão. **O envio ao cliente (e-mail, portal ou outro canal) não está implementado.** Alteração ou nova versão de um orçamento rejeitado também não está disponível.
- Eventos `BUDGET_CREATED`, `BUDGET_APPROVED`, `BUDGET_REJECTED` e `PAYMENT_APPROVED` são gravados no mesmo banco que a alteração do orçamento. Um dispatcher os publica em `billing.exchange` com routing key `billing.<tipo em minúsculas>`. Cada mensagem contém `eventId`, `eventType`, `orderId`, `budgetId` e `occurredAt`. A fila durável `billing.events.queue` retém uma cópia; cada serviço consumidor deve declarar sua própria fila e binding e deduplicar pelo `eventId`. A entrega é **pelo menos uma vez**, não exatamente uma vez.
- A atualização para `ENTREGUE` foi escolhida para este projeto após confirmação do usuário. Após pagamento aprovado, o dispatcher consulta a OS e somente envia `PUT /orders/{id}/status` se ela estiver `FINALIZADA`; se já estiver `ENTREGUE`, considera a operação concluída. Outros estados ou indisponibilidade são reprocessados. **Pagamento não comprova a entrega física do veículo.** O endpoint atual da OS não oferece atualização condicional: uma mudança concorrente entre a consulta e o PUT pode causar uma transição indevida. É necessário acrescentar uma operação condicional/idempotente no OS Service antes de usar este fluxo em produção.
- Trata-se de uma **coreografia proposta**, com outbox local e reprocessamento, não de uma saga completa com compensações distribuídas. A aprovação/rejeição ainda não altera a OS nem encaminha a execução; não há cancelamento automático, estorno, compensação ou conciliação de pagamento. Se um pagamento confirmado mudar para outro estado, a notificação falha e exige intervenção manual. Pagamentos duplicados exigem conciliação/estorno manual. A criação remota de uma preferência pode ser repetida após falha de rede ou do banco. Cada serviço precisa implementar sua parte da saga, deduplicação e compensações, incluindo pagamento/estorno, antes de se considerar o requisito atendido.
- A chave compartilhada protege a API de orçamentos, mas **não implementa identidade do cliente, autorização por OS ou segregação de funções**. Use-a apenas entre serviços confiáveis atrás de gateway com autenticação/autorização apropriadas e TLS. O OS Service consultado também precisa de controles de acesso. Não exponha diretamente esta aplicação em produção sem esses controles.
- Ainda são necessários testes integrados reais com os três serviços, rastreamento distribuído, avaliação de segurança, infraestrutura de produção, evidências de deploy e vídeo. A regra de proteção da `main` é configurada no GitHub, não pelo código.

## Fluxo e API

Base local: `http://localhost:8082/api/v1`. As rotas `/budgets` exigem `X-API-Key`.

| Método | Rota | Ação |
|---|---|---|
| POST | `/budgets` | Criar orçamento para OS em `EM_DIAGNOSTICO` ou `AGUARDANDO_APROVACAO` |
| GET | `/budgets/{id}` | Consultar orçamento e situação de pagamento |
| POST | `/budgets/{id}/decision` | Aprovar ou rejeitar: `{"approved":true}` |
| POST | `/budgets/{id}/checkout` | Criar/reutilizar preferência Checkout Pro para orçamento aprovado |
| POST | `/webhooks/mercado-pago` | Receber notificação autenticada do Mercado Pago |

```bash
curl -X POST http://localhost:8082/api/v1/budgets \
  -H "X-API-Key: $BILLING_API_KEY" -H "Content-Type: application/json" \
  -d '{"orderId":1,"description":"Reparo","amount":150.00}'
```

Após aprovação, chame o endpoint `checkout` e apresente o `checkoutUrl` ao cliente. O webhook valida HMAC-SHA256 com o segredo da aplicação, consulta o pagamento na API do Mercado Pago com token privado e só registra `PAID` se o pagamento estiver aprovado, referência, valor e moeda BRL coincidirem e existir checkout. Notificações repetidas do mesmo pagamento são idempotentes. Uma notificação não aprovada não marca o orçamento como pago. Configure no Mercado Pago a URL pública HTTPS `https://<host>/api/v1/webhooks/mercado-pago` e o evento de pagamentos; o `data.id` da query é usado na verificação da assinatura.

Documentação interativa: `/api/v1/swagger-ui/index.html`; OpenAPI: `/api/v1/v3/api-docs`. Referências: [Checkout Pro](https://www.mercadopago.com.br/developers/en/docs/checkout-pro/overview), [notificações](https://www.mercadopago.com.br/developers/en/docs/checkout-pro/payment-notifications).

## Execução local

Java 21 e Docker Compose são necessários. Configure as variáveis de ambiente antes de subir o serviço:

| Variável | Uso |
|---|---|
| `BILLING_API_KEY` | Chave privada para chamadas à API de orçamento |
| `MERCADO_PAGO_ACCESS_TOKEN` | Token privado da conta Mercado Pago |
| `MERCADO_PAGO_WEBHOOK_SECRET` | Segredo de assinatura do webhook |
| `MERCADO_PAGO_NOTIFICATION_URL` | URL HTTPS pública do webhook |
| `OS_SERVICE_URL` | Base REST da OS, padrão `http://host.docker.internal:8081/api/v1` no Compose |
| `DATABASE_PASSWORD` | Senha PostgreSQL; padrão local `billing` |

```bash
docker compose up --build
```

O Compose cria PostgreSQL e RabbitMQ próprios para desenvolvimento. Para receber eventos entre serviços, eles precisam se conectar ao **mesmo broker**, com filas distintas; o broker local deste Compose é independente do usado pelo Compose do OS Service. A URL HTTPS precisa encaminhar requisições ao Billing. Sem credenciais Mercado Pago, a criação de checkout e a verificação de webhook falham explicitamente.

Para rodar fora do Compose, use `DATABASE_URL=jdbc:postgresql://localhost:5433/billing_service`, `DATABASE_USER=billing`, `RABBITMQ_HOST=localhost`, `RABBITMQ_PORT=5673` e `OS_SERVICE_URL=http://localhost:8081/api/v1`, além das variáveis acima. Inicie o OS Service e execute `./mvnw spring-boot:run` ou `mvnw.cmd spring-boot:run` no Windows.

## Testes, qualidade e operação

`./mvnw verify` executa testes unitários, cenário Cucumber do Billing e testes de integração com H2 e serviços simulados. O JaCoCo exige pelo menos 80% de cobertura de linhas do serviço e gera `target/site/jacoco/index.html` e `jacoco.xml`. O workflow publica o relatório como artefato `jacoco-report`.

O workflow `Billing CI/CD`, como o do OS Service, executa `sh ./mvnw -B clean verify` no runner do GitHub em PRs e pushes para `main` ou `feature/**`. O build inclui testes, cenário BDD e verificação de cobertura mínima de 80% pelo JaCoCo, sem credenciais Sonar. O mesmo comando pode ser executado localmente; no Windows, use `.\mvnw.cmd -B clean verify`. Esta configuração não executa análise estática nem quality gate SonarQube: esse requisito do desafio ainda precisa ser atendido.

Após push validado em `main`, o workflow publica uma imagem GHCR identificada pelo SHA e implanta no namespace `billing-service`, acompanhando o rollout. Configure no environment `production` o secret `KUBE_CONFIG` com o kubeconfig completo em texto, de um cluster acessível ao runner com permissões para o deploy. Provisione previamente o namespace, PostgreSQL, RabbitMQ e o secret Kubernetes `ghcr-pull` com acesso de leitura à imagem GHCR. Crie também o secret Kubernetes `billing-secrets` com `DATABASE_PASSWORD`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `BILLING_API_KEY`, `MERCADO_PAGO_ACCESS_TOKEN`, `MERCADO_PAGO_WEBHOOK_SECRET` e `MERCADO_PAGO_NOTIFICATION_URL`; ajuste `k8s/configmap.yaml` se os endereços diferirem da infraestrutura compartilhada. Não armazene segredos no repositório. O workflow não provisiona banco, broker, ingress ou TLS.

Configure no GitHub a proteção de `main` para exigir pull request e o check `Build, testes e qualidade` após o workflow estar disponível no PR.

Health e métricas estão em `/api/v1/actuator/health` e `/api/v1/actuator/prometheus`. Configure coleta Prometheus, alertas para falhas/repetições do outbox e ferramentas de observabilidade da fase anterior. Segredos, branch protection, autenticação do registry e ambiente externo não são configuráveis automaticamente por este repositório.
