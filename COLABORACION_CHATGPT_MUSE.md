# Colaboración ChatGPT + Muse AI — VisionClaw existente

## Objetivo fijado por el propietario
Arreglar **la misma VisionClaw** (no crear otra APK/app por separado), usando **Xiaomi 14**, **Ray-Ban Meta Gen 2**, **OpenClaw en Termux** y **Muse AI** para visión/voz/acciones. Trabajar desde GitHub, sin pedir al propietario copiar y pegar código, sin simulación de cámara y sin añadir servicios de pago sin autorización.

## Punto de partida verificado por ChatGPT (27 octubre 2026)
- Repositorio: `Esrs2024/VisionClaw`.
- Base a preservar: `gemini-direct`, `samples/CameraAccessAndroid/`.
- `main` y `gemini-direct` han divergido; NO fusionar ni sobreescribir sin revisión y respaldo.
- En `gemini-direct` hay `gemini/`, `muse/`, `openclaw/` y Meta DAT Android 1.0.1. `main` contiene otros cambios de OpenClaw local y la aplicación independiente `morethantiles-inspector`; mantenerlos intactos.
- CI correcto 23/10/2026: https://github.com/Esrs2024/VisionClaw/actions/runs/39997408782, APK `VisionClaw-v6-Muse-Voz-Diagnostico-Xiaomi14-arm64` (no prueba de hardware).
- Fallos comunicados por propietario: cierre en el móvil, fallo enlace/cámara gafas, WebRTC vacío y ausencia de audio o respuesta del agente. **Ninguno de ellos está demostrado como solucionado.**

## División de trabajo propuesta
**Muse AI:** auditar arranque de la app, excepciones, manifiesto/Gradle, ciclo de permisos, registro Meta DAT y recepción de fotogramas de cámara real; proponer cambios en rama `ai/muse-...` basada en `gemini-direct`. Adjuntar evidencia de compilación y especificar pruebas que requieren gafas físicas.

**ChatGPT:** auditar ruta cámara real / voz Bluetooth → OpenClaw Gateway local en Termux (`127.0.0.1:18789`) → modelo Muse → respuesta TTS/Bluetooth. Preparar modificaciones en ramas `ai/chatgpt-...` basadas en `gemini-direct`, con PR separado y evidencia.

## Protocolo de coordinación
1. Leer `AGENTS.md` y este documento; identificar fallos comprobados frente a posibles.
2. Dejar mensajes, commits y resultados en las **pull requests** de GitHub. La opción Issues está desactivada en este repositorio.
3. No modificar simultáneamente los mismos archivos; cada tarea en rama y PR independientes, dirigidas a `gemini-direct` para esta versión.
4. No mezclar cambios de `main`, `morethantiles-inspector` o gateway alojado sin comprobar primero si forman parte de la app actual.
5. No guardar claves ni tokens en el repositorio; no cambiar firma/identificador de la app sin autorización. No afirmar «funciona con gafas» sin prueba física.
6. Prioridad de verificación: **arranque → conexión Ray-Ban → fotograma real → micrófono real → voz reproducida → OpenClaw → respuesta de Muse**.
7. Ningún agente podrá hablar automáticamente con el otro por tener este documento. Muse necesita acceso real al repositorio y leer esta PR. Informar al propietario de los bloqueos reales, no exigirle operaciones manuales repetidas.

## Registro de avances
- **ChatGPT (27/10/2026):** inspeccionados los árboles `main`/`gemini-direct`, CI exitoso `gemini-direct` y archivo AGENTS; detectada divergencia de ramas. Pendiente auditoría de código y prueba física.
- **Muse AI:** pendiente de confirmación de acceso GitHub y de su análisis. Que añada aquí los commits o una PR enlazada.
