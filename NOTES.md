# Notas de entrega

Java 17, Maven y repositorios en memoria. `mvn test`: 102 pruebas superadas.

## Decisiones (3)

1. **Reservas.** Elegimos una cola FIFO: cuando se devuelve una copia, se asigna al primer socio de la cola y se retiene durante 48 horas. Si no la recoge, la reserva pasa a EXPIRED y la copia se ofrece al siguiente socio o queda disponible.
También decidimos:
- No permitir reservas duplicadas del mismo socio y título.
- No permitir dos préstamos activos del mismo título al mismo socio.
- Si el socio ya tiene ese título cuando intenta recogerlo, pierde el turno.
  Descartamos prioridades por nivel, enviar al socio al final de la cola y penalizaciones automáticas por no recoger.Cambiaríamos esta decisión si el negocio exigiese conservar una reserva ya asignada hasta que el socio pagase, en vez de hacerle perder el turno, o si quisiera bloquear también las devoluciones.

2. **Multas y bloqueos.**
   Elegimos bloquear cuando la deuda sea estrictamente superior a 10,00 €. Se bloquean:
- Nuevos préstamos.
- Renovaciones.
- Entrada en una cola.
  Una deuda de exactamente 10,00 € todavía permite esas operaciones. Las devoluciones siempre se permiten. Si el socio intenta recoger una reserva con deuda superior al umbral, pierde el turno y la copia pasa al siguiente socio.
  Las multas se calculan al devolver, cobrando 0,20 € por cada bloque completo de 24 horas desde dueAt. Las fracciones no se redondean hacia arriba.
  Descartamos bloquear todas las operaciones, permitir entrar en cola con deuda superior al umbral y cobrar por día natural. Cambiaríamos la decisión si el negocio exigiese bloquear reservas o calcular multas de préstamos todavía abiertos.

3. **Políticas y tiempo.**
   Elegimos que las reglas importantes sean configurables mediante políticas:
- Duración del préstamo.
- Límite de préstamos activos.
- Límite de renovaciones.
- Tarifa de multa.
- Umbral de bloqueo.
- Ventana de recogida.

## Fuera de alcance

API, interfaz, autenticación, pagos, almacenamiento duradero y configuración externa.
En este ejercicio no se incluye la capa HTTP/REST ni la integración con un sistema de autenticación real; la persistencia se simula con repositorios en memoria y el modelo de negocio permanece aislado del almacenamiento físico. Tampoco se cubren pagos reales, notificaciones, despliegue ni configuración operativa externa.

## Qué sé que está roto y entrego igualmente

- **Reservas vencidas:** no hay una tarea automática que las revise. Aunque hayan pasado las 48 horas, una reserva puede seguir figurando como `ASSIGNED` hasta que alguien realiza una operación relacionada. Entonces se libera la copia o se ofrece al siguiente socio; mientras tanto, puede parecer retenida más tiempo del debido.
- **Multas de préstamos sin devolver:** la multa de ese préstamo solo se calcula y añade al saldo al devolverlo. Si un socio conserva un préstamo vencido, podría pedir otro o entrar en cola (si cumple las demás reglas) porque ese retraso aún no aumenta su deuda registrada. Renovar el préstamo vencido sí está bloqueado.
- **Concurrencia y fallos:** `synchronized (copies)` evita carreras solo entre servicios que comparten el mismo repositorio en una JVM. Además, guardar copia, préstamo y reserva exige varios `save`: si uno falla, pueden quedar datos contradictorios, por ejemplo una copia marcada como prestada sin su préstamo. Con PostgreSQL harían falta una transacción, bloqueo de filas y una restricción de préstamo activo único por copia.

## Siguientes 30 minutos

Primero probaría qué pasa si falla el guardado a mitad de un préstamo o una devolución, para que la copia y el préstamo no queden con estados contradictorios. Después decidiría cómo calcular la multa de un préstamo aún sin devolver sin cobrar dos veces el mismo retraso. Por último, explicaría cómo proteger estas operaciones con una transacción al pasar a una base de datos.

## Uso de IA

Usé la IA para entender el enunciado y preparar borradores del modelo y de las pruebas. También le pedí casos con varias peticiones a la vez: varios socios por la última copia y dos intentos de reservar, recoger o devolver. Era útil porque podía comprobar con los tests y la demo que solo prosperase una petición, sin importar quién ganase.

No acepté todo tal cual. Un test usaba `0.20` en `Money`, dando a entender que la tarifa de multa pertenecía al propio dinero; al recordar que debía poder cambiar, corregí el test y dejé la tarifa en `FinePolicy`. Otro test prestaba tres copias del mismo título a un socio: falló y lo rehice con tres títulos distintos. Cuando decidimos bloquear la cola por deuda superior a 10 €, también tuve que corregir un test antiguo que la permitía. Ejecuté `mvn test` y la demo para comprobar los resultados, incluida una sola multa ante dos devoluciones simultáneas. Me queda sin verificar a fondo qué ocurre si varios hilos cambian las políticas al mismo tiempo.

Y, por último, el cambio más delicado fue el de `borrow` con `Hold`. En un primer momento el diseño y la demo estaban pensados para que `borrow` fallara con un error cuando no había copia disponible, y la reserva se pedía explícitamente con `placeHold`. Eso no encajaba con la regla real del negocio: cuando no hay copia disponible, el mismo `borrow` debe poder devolver una `Action` que sea `Hold` y no solo `Loan`. Tuve que corregirlo manualmente en `LibraryService.borrow(...)` para que, si no hay copia disponible o si el título ya tiene cola, devuelva el `Hold` correspondiente en vez de lanzar la excepción antigua. Ese ajuste produjo varios errores de integración: tests que seguían esperando `Loan`, mensajes como `the title has a waiting queue`, `member already has an active hold for this title`, `no se crearon las dos reservas esperadas`, `la copia devuelta no se asignó a la reserva`, `la devolución tardía no asignó la copia a la siguiente reserva` y la demo de `ConcurrentDemo` siguiendo suposiciones antiguas. La solución fue alinear el contrato del servicio, el modelo (`Action`, `Loan`, `Hold`) y la lógica de espera FIFO, además de actualizar la demo para que refleje la realidad del dominio en lugar de la idea inicial de `borrow` siempre devolviendo préstamo.

## Diagrams generated with Archify skill

Me he tomado la libertad de generar estos diagramas de arquitectura de runtime usando la skill **archify** (tt-a1i/archify) incluida en este proyecto. El diagrama resultante (`diagrams/library-architecture.html`) ofrece una visión general del sistema en una sola JVM: miembros de la biblioteca, API REST, servicio de biblioteca central, repositorio de copias, cola de reservas FIFO y nivel de membresía. El diagrama pasa validación showcase (0 errores, 0 warnings) y ha sido verificado en 4 tamaños de viewport (1440×900 a 2048×1320) sin problemas de solapamiento ni legibilidad. La salida es un HTML autónomo con cambio de tema, exportación a PNG/SVG y navegación interactiva. Queda a disposición para iteración futura si el modelo evoluciona hacia microservicios o persistencia externa.
