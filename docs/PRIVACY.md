# Aviso de privacidade do Lume

**Status: versão preliminar — não é uma auditoria completa nem uma política final para distribuição pública.** O projeto precisa terminar a revisão do fluxo de dados, bibliotecas e serviços desta variante e atualizar este documento antes de ser apresentado como política definitiva.

## Quem opera o quê

Lume é um cliente Android independente baseado no código aberto do Signal Android. O projeto Lume não opera a infraestrutura Signal. A versão atual está configurada para se conectar à rede e aos serviços Signal; o tratamento de dados realizado por esses serviços é descrito pela [Política de Privacidade da Signal](https://signal.org/legal/). Esse documento se aplica aos serviços da Signal, não transforma o Lume em um aplicativo oficial e não descreve, por si só, todos os componentes desta compilação independente.

## Dados e funcionamento

Para usar a rede Signal, o cliente herda o fluxo de registro por número de telefone e as funções de mensagens, chamadas, descoberta de contatos e notificações da base Signal Android. Mensagens e chamadas da rede Signal são projetadas para usar criptografia de ponta a ponta; isso não significa que todos os dados auxiliares, dados locais ou dados processados por bibliotecas de terceiros sejam inacessíveis a esses serviços.

As conversas e outros conteúdos podem ser armazenados localmente no aparelho conforme as funções do aplicativo e as escolhas de backup e transferência feitas pelo usuário. O projeto não adicionou, nesta etapa de rebranding, uma conta Lume ou um servidor próprio para armazenar conversas.

## Serviços de terceiros e auditoria pendente

A configuração atual do código contém bibliotecas do Firebase e do Google Play Services, além de integrações relacionadas a notificações, mapas e autenticação. A presença de uma biblioteca no código não prova por si só que ela foi executada ou quais dados ela enviou em cada versão; isso exige auditoria do build e teste de tráfego. Essa auditoria ainda não foi concluída. Portanto, não é correto prometer que o Lume não transmite dados a terceiros ou que coleta apenas os dados descritos neste resumo.

Antes de uma distribuição pública, o mantenedor precisa identificar as bibliotecas incluídas na variante final, observar os dados efetivamente transmitidos, documentar os destinatários e finalidades, e substituir este aviso por uma política completa e revisada.

## Controles do usuário

O usuário pode revisar as permissões Android, negar permissões opcionais, deixar de usar o aplicativo e desinstalá-lo. A desinstalação pode remover dados locais do aplicativo; ela não apaga mensagens que já chegaram a outros participantes nem necessariamente exclui dados mantidos pelos serviços da Signal ou por terceiros segundo suas próprias regras.

## Contato

Questões sobre o código do projeto podem ser encaminhadas pelo [rastreador de issues do Lume](https://github.com/contactokakashigamesofc24-dotcom/Signal-Android-Connect/issues). Para solicitações sobre dados tratados pela Signal em seus serviços, consulte a política e os canais oficiais da Signal.


---

# Lume Privacy Notice (English)

**Status: preliminary — this is not a complete audit or final public-distribution privacy policy.** The project must finish reviewing this flavor's data flows, libraries, and services and update this document before presenting it as a definitive policy.

## Who operates what

Lume is an independent Android client based on open-source Signal Android code. The Lume project does not operate Signal infrastructure. The current source is configured to connect to the Signal network and services; processing performed by those services is described in the [Signal Privacy Policy](https://signal.org/legal/). That policy applies to Signal-operated services; it does not make Lume an official app or by itself describe every component of this independent build.

## Data and operation

To use the Signal network, the client inherits the phone-number registration flow and messaging, calling, contact-discovery, and notification features of the Signal Android codebase. Messages and calls on the Signal network are designed to use end-to-end encryption; this does not mean auxiliary data, local data, or data processed by third-party libraries is inaccessible to those services.

Conversations and other content may be stored locally on the device according to app features and the user's backup and transfer choices. The project did not add a separate Lume account or a Lume-operated conversation-storage server as part of this rebranding change.

## Third-party services and pending audit

The current source configuration contains Firebase and Google Play Services libraries, as well as integrations related to notifications, maps, and authentication. A library's presence in source does not by itself prove that it ran or what data it sent in a given build; that requires auditing the release build and testing its network traffic. This audit is not complete. Therefore, it would be inaccurate to promise that Lume sends no data to third parties or only handles the data summarized here.

Before public distribution, the maintainer must identify libraries in the final variant, inspect data actually transmitted, document recipients and purposes, and replace this notice with a complete, reviewed policy.

## User controls

Users can review Android permissions, deny optional permissions, stop using the app, and uninstall it. Uninstallation may remove local app data; it does not remove messages already delivered to other participants and may not delete data held by Signal services or third parties under their own rules.

## Contact

Questions about project code can be submitted through the [Lume issue tracker](https://github.com/contactokakashigamesofc24-dotcom/Signal-Android-Connect/issues). For requests about data processed by Signal in its services, consult Signal's policy and official channels.
