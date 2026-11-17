Feature: Cobranca de ordem de servico
  Scenario: Orcamento aprovado e pagamento confirmado
    Given uma OS em diagnostico
    When um orcamento e criado e aprovado
    And o Mercado Pago confirma o pagamento
    Then a cobranca fica paga e um evento de pagamento e registrado
