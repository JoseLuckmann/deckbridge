# Risk Register — hipóteses, severidade e mitigação

**Atualização:** 2026-10-21. Probabilidade relativa até existir HIL; não confundir risco com defeito observado.

| Risco | Prob. | Impacto | Estratégia / owner técnico | Gate |
|---|---|---|---|---|
| Algum aparelho não aguentar Mixxx com dois decks | alta desconhecida | alta | OD-01: não trocar de modelo como plano; medir na hora e desligar waveforms/efeitos pesados. Dois decks estáveis só depois do benchmark daquela máquina | G0 |
| Alimentação insuficiente para DDJ USB e tela | média | alta | fonte 5A Pi5 apropriada, orçamento/medição, hub alimentado | G0/G2 |
| Firmware DDJ-400 gerar pitch fantasma | média | alta | revisar versão e update oficial com PC/Mac | G0 |
| Mixxx 2.5.6 não importar determinada export RB7 | média | alta | matriz legacy, fallback UX, patch isolado com upstream, no OneLibrary promise | G1 |
| Dados de cues/loops não serem equivalentes | alta | média | provenance e diff semântica, comunicar diferença | G1 |
| Parser OneLibrary exigir acesso não autorizado | alta | média | escopo opcional, fallback legacy, sem extração de segredo | P5 |
| Queda de energia corromper SD local | média | alta | DB transacional, backup, image rollback, teste power-loss | G2 |
| Skin elevada em custo GPU / CPU | média | alta | referência 800x480 e 1024x600 (OD-02); em hardware fraco desligar waveform e efeitos pesados (OD-01) | G1/G2 |
| USB removido no meio do set parar áudio | média | alta | alerta, educação operacional, estudar preload explícito | G1/G2 |
| Fork divergir do upstream Mixxx | média | média | patchset pequeno e ADR, pipeline rebase/retest | contínuo |
| Uso indevido de nomes/assets proprietários | média | alta | marca independente, licença/NOTICE/SBOM auditados | G2 |
| IoT/PWA habilitada expor API | baixa com default seguro | alta | loopback off por default, pairing físico, firewall/auth | G3 |
| Eventos/IA degradarem thread de áudio | média | alta | evitar RT, bridge async, quotas, soak | G3/G4 |
| Armazenar músicas/cache inadvertidamente | média | média/alta | metadados e waveform local só quando previsto, quotas/consentimento | G2/P3 |
| Matriz teste sem hardware real gerar falso positivo | média | alta | reportar BLOCKED_HARDWARE e evidências rastreáveis | todos |
| Android em geral não separar master e fone (canais 3–4) | alta | alta | Prova A0. Se falhar, outro tablet antes de driver próprio (OD-14, provisório, confirmar com o dono) | A0 |
| 4 canais no Galaxy Tab S6 Lite do dono | baixa neste aparelho, só pelo relato | alta | Relato do dono em 21/10/2026: com o djay, master e fone saíram separados. Não é medição nossa. Modelo exato não confirmado (SM-P610/P615 ou 2022/2024). A prova A0 segue BLOCKED_HARDWARE | A0 |
