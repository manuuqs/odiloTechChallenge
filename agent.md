# Guía de negocio para agentes

Este documento orienta el diseño y los tests de la biblioteca de préstamos de copias físicas. La fuente de requisitos es `Letmino Tech Challenge.pdf`; las **tres decisiones de entrega** y su justificación están en `NOTES.md`. Consulta ambos documentos antes de resolver una ambigüedad. Mantén esta guía centrada en reglas duraderas, no en un inventario de archivos ni en el progreso de una sesión.

## Forma de trabajar

- Propón primero los tipos, después las políticas y finalmente los casos de uso. Cada regla importante debe tener una prueba que demuestre su efecto observable.
- Distingue los requisitos del enunciado de las elecciones de negocio recogidas en `NOTES.md` y de los detalles aún por decidir. No conviertas una suposición en regla silenciosamente.
- Trabaja de menor a mayor nivel: valores e identificadores → entidades y transiciones → políticas → repositorios en memoria → servicio de aplicación → concurrencia e integración.
- Mantén Java 17, Maven y JUnit 5; usa persistencia en memoria. No añadas infraestructura ajena a las necesidades del dominio.
- Si cambia una decisión, actualiza sus tests y `NOTES.md` sin añadir una cuarta decisión de entrega. Deja en `NOTES.md` el estado incompleto, los siguientes 30 minutos y el uso de IA; aquí no dupliques esas secciones.

## Lenguaje del dominio

- **Title**: obra bibliográfica identificada por `TitleId`; no es un ejemplar prestable.
- **Copy**: ejemplar físico identificado por `CopyId`, asociado a un `TitleId`. Estados: `AVAILABLE`, `ON_LOAN`, `HELD`.
- **Member**: persona identificada por `MemberId`, con un `Tier` y un saldo pendiente. Sus préstamos y reservas se consultan por identificador, sin duplicar colecciones mutables en el miembro.
- **Loan**: préstamo de una copia concreta a un miembro, con inicio, vencimiento, devoluciones y renovaciones. Una copia puede tener muchos préstamos históricos, pero **como máximo uno activo**.
- **Hold**: solicitud de un miembro sobre un título (no sobre una copia concreta). Estados: `WAITING` → `ASSIGNED` → `COLLECTED` o `EXPIRED`. Al asignarla se registra qué copia quedó retenida y hasta cuándo.
- **TierPolicy**: días de préstamo, máximo de préstamos activos y máximo de renovaciones. Un `OptionalInt` vacío indica renovaciones ilimitadas.
- **FinePolicy**: importe por cada bloque completo de 24 horas de retraso y umbral de bloqueo. `Money` solo representa euros y céntimos: no conoce el precio de la multa.
- **PolicyProvider**: fuente reemplazable de políticas de niveles, multas y ventana de recogida. La configuración inicial vive en su implementación en memoria; las reglas del servicio consultan la política vigente.

Relaciones relevantes: `Title 1 → N Copy`; `Title 1 → N Hold`; `Member 1 → N Loan/Hold`; `Copy 1 → 0..1 Loan activo`. La cola pertenece al título; el préstamo pertenece a una copia.

## Contrato de repositorios en memoria

- `save` sustituye la entidad por identificador (`upsert`); las validaciones de negocio y los duplicados de reservas se comprueban en el servicio.
- Las búsquedas de identificadores inexistentes devuelven `Optional.empty()`; las consultas devuelven listas no modificables. Esto no convierte las entidades mutables contenidas en copias profundas.
- `CopyRepository` consulta por título y disponibilidad; `LoanRepository` distingue préstamos activos por ausencia de devolución; `HoldRepository` obtiene reservas `WAITING`/`ASSIGNED` y ordena FIFO por creación y orden de registro en caso de empate.
- `ConcurrentHashMap` protege operaciones individuales, no una transacción entre copias, préstamos y reservas. Toda modificación que compita por una copia o su cola debe usar el mismo bloqueo compartido durante la lectura, decisión y escritura; no basta con bloquear una instancia de `LibraryService`. El bloqueo evita carreras entre servicios que comparten repositorios, pero no revierte cambios si falla un `save`.

## Parámetros iniciales

| Nivel | Plazo | Préstamos activos | Renovaciones |
| --- | ---: | ---: | ---: |
| Standard | 14 días | 3 | 2 |
| Student | 28 días | 5 | 3 |
| Staff | 56 días | 10 | ilimitadas |

- Multa inicial: **0,20 € por cada bloque completo de 24 horas de retraso**.
- Bloqueo: deuda **estrictamente superior a 10,00 €** impide pedir préstamos, renovarlos y entrar en la cola; 10,00 € exactos no bloquean y las devoluciones siempre se permiten.
- Ventana inicial para recoger una copia asignada: **48 horas**.
- Estos números deben concentrarse en políticas modificables; una actualización afecta operaciones futuras y no reescribe vencimientos ya concedidos.

## Tres decisiones de negocio acordadas

1. **Reservas**: cola FIFO; se retiene la copia 48 horas para el primer miembro. Si no la recoge, la reserva expira y se ofrece al siguiente. No se crean reservas duplicadas del mismo miembro y título. Si al intentar recoger ya tiene un préstamo activo de ese título, pierde el turno (`EXPIRED`) y la copia se ofrece al siguiente miembro FIFO o queda disponible. No hay prioridades por nivel ni penalización automática por no recogerla.
2. **Multas**: por encima del umbral se bloquean nuevos préstamos, renovaciones y entrar en la cola, pero se permiten devoluciones. Si al recoger una reserva supera el umbral, su reserva pasa a `EXPIRED` anticipadamente y se ofrece la copia al siguiente miembro; llegar al límite de préstamos solo rechaza la recogida, sin perder el turno antes de las 48 horas. La multa se acumula al devolver: tarifa vigente por cada 24 horas completas de retraso desde `dueAt`, sin redondear fracciones hacia arriba.
3. **Niveles y fechas**: políticas consultables y sustituibles en ejecución; usar `Clock` inyectado e `Instant` en UTC. Una actualización de política no cambia automáticamente el vencimiento almacenado de un préstamo existente; una renovación explícita se evalúa con las reglas vigentes y suma su duración al vencimiento anterior.

Las alternativas rechazadas y el dato de negocio que cambiaría cada decisión están en `NOTES.md`.

## Casos de uso e invariantes

### Solicitar un préstamo

Comprobar existencia del miembro y del título, saldo pendiente y límite de préstamos activos según el nivel. No conceder un segundo préstamo activo del mismo título al mismo miembro, aunque sea otra copia. Elegir una copia disponible sin saltarse una cola previa. Comprobar disponibilidad, marcar la copia como prestada y crear el préstamo bajo **el mismo bloqueo compartido en memoria**; varios `save` no equivalen a una transacción con reversión. Calcular el vencimiento con la política vigente y guardarlo en el préstamo.

### Entrar en la cola

Cuando no haya ejemplar asignable, crear un `Hold` en estado `WAITING` sobre el título. Mantener el orden de llegada incluso si dos reservas comparten la misma marca temporal; no admitir otra reserva activa para el mismo miembro y título ni reservar un título que ya tiene prestado. Consultar la política de multas vigente: deuda estrictamente superior al umbral bloquea entrar en la cola; exactamente 10,00 € con el umbral inicial sí lo permite. El límite de préstamos activos no bloquea la entrada en la cola. Estar en la cola no equivale a tener un préstamo.

### Devolver, asignar y recoger

Permitir devolver aun con deuda. Una devolución cierra una sola vez su préstamo; si hay cola, asigna esa **copia concreta** al primer miembro y la retiene hasta `assignedAt + ventana de recogida`. Mientras esté retenida no es una copia disponible. Una recogida válida ocurre **antes** de `expiresAt`, nunca en el instante exacto de expiración; debe convertirse en préstamo respetando las reglas para nuevos préstamos. Volver a comprobar que el miembro no tenga un préstamo activo del mismo título; si ya lo tiene, finalizar anticipadamente su reserva como `EXPIRED` y ofrecer la copia al siguiente FIFO o liberarla si no hay nadie. Solo alcanzar el límite de préstamos rechaza la recogida sin perder el turno. Conservar la reserva finalizada en el historial, sin dejarla en la cola activa. Si vence sin recogida, liberar la asignación y evaluar al siguiente miembro FIFO. Sin scheduler, procesar expiraciones al llamar a casos de uso relacionados o explícitamente a `expireHold`. La coordinación de estas operaciones corresponde al servicio, no solo a `Hold`.

### Renovar

Exigir préstamo activo y copia `ON_LOAN` coherente, miembro no bloqueado y renovaciones restantes según la política vigente de su nivel; Staff puede renovar sin límite inicial. Rechazar la renovación en `dueAt` o después, y si **otra persona** tiene una reserva `WAITING` de ese título. Calcular el nuevo vencimiento como `dueAt` anterior + días de préstamo de la política vigente (no desde la hora de solicitud). Solo una renovación válida incrementa `renewalCount`; rechazos por deuda, vencimiento, cola o límite no consumen intentos. Un cambio de política por sí solo no modifica un préstamo: solo la operación explícita de renovación puede cambiar su fecha de vencimiento.

### Calcular multas

Usar `FinePolicy` y `Money` con `BigDecimal`, nunca `double`. Al devolver, cobrar `dailyOverdueFine` de la política vigente por cada bloque completo de 24 horas desde `dueAt`: antes y exactamente en `dueAt` la multa es cero; con 23 h 59 min de retraso es cero; a las 24 h es una tarifa; a las 48 h son dos. Sumarla una sola vez al saldo existente del prestatario, sin sobrescribir deudas previas ni cobrarla al miembro que recibe la copia. Una devolución duplicada no vuelve a cobrar. No bloquear devoluciones por deuda. No hay acumulación automática de multas de préstamos abiertos: el saldo se actualiza al devolver. No hay pagos implementados ni trazabilidad histórica de multas en el alcance actual.

## Tiempo, configuración y concurrencia

- Obtener la hora desde `Clock` inyectado en la capa de aplicación; pasar `Instant` a las entidades. Evitar `Instant.now()` o `LocalDateTime.now()` dentro del dominio.
- Probar límites exactos: recogida inmediatamente antes de expirar, exactamente al expirar y después; devolución al vencimiento; deuda de 10,00 € frente a más de 10,00 €.
- La condición de carrera de la última copia es: A y B la ven disponible, ambos intentan asignarla y podrían crear dos préstamos activos. Proteger toda la secuencia de lectura, decisión y escritura con un bloqueo coherente en memoria, incluidos los efectos sobre la cola. Un `synchronized` de una única instancia no protege distintas instancias de la aplicación.
- Si se migra a PostgreSQL con varias instancias, usar transacción con bloqueo de fila (`SELECT ... FOR UPDATE`) o actualización condicional comprobando filas afectadas, más restricciones de integridad para impedir préstamos activos duplicados.
- Los cambios de configuración en memoria no persisten entre reinicios; la interfaz `PolicyProvider` permite sustituir la fuente más adelante sin dispersar constantes por el dominio.

## Estrategia de tests

Usar JUnit 5 y `Clock.fixed` o un reloj controlable. Priorizar pruebas que distingan comportamientos válidos e inválidos, no tests que solo repitan getters. Ejecutar `mvn test` tras cada etapa significativa. Al cambiar una regla, cotejar su descripción en `README.md`, `NOTES.md` y esta guía con el comportamiento probado.

- **Valores y políticas**: entradas inválidas, importes en céntimos, parámetros iniciales de los tres niveles, configuración actualizada en ejecución y vencimientos existentes sin cambio retroactivo.
- **Transiciones**: estados iniciales, asignación y recogida válidas, expiración exacta, devolución duplicada, fechas incoherentes y transición rechazada sin estado parcial.
- **Préstamos**: última copia, límite de cada nivel, deuda igual y superior al umbral, dos copias del mismo título para un miembro, devolución aunque exista deuda.
- **Reservas**: FIFO con varios miembros, reserva duplicada, deuda exactamente en el umbral frente a superior, copia retenida no prestable, expiración y oferta al siguiente, recogida antes/en/después del límite y pérdida del turno por título ya prestado (con o sin sucesor).
- **Renovaciones y multas**: límite de Standard/Student, ilimitadas en Staff, espera de otras personas, vencimiento anterior y fecha frontera exacta, políticas actualizadas y rechazo sin consumir renovaciones; multa a las 0/24/48 h, fracciones, acumulación de saldo y devolución duplicada.
- **Concurrencia**: coordinar dos solicitudes a la última copia con barreras o `CountDownLatch`; verificar que hay un único préstamo de esa copia y que la otra solicitud tiene un resultado definido. No usar `Thread.sleep` para sincronizar tests.

Cuando una regla ambigua no tenga decisión tomada, anota el supuesto y crea una prueba que haga visible el comportamiento elegido antes de integrarlo. Mantén los tests al mismo nivel que la regla: valores y transiciones en el dominio, flujos completos y carreras en el servicio.
