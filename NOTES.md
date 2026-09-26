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
La reserva se solicita aparte cuando `borrow` no encuentra copia. Cuando no hay copia disponible, borrow devuelve un error y no crea automáticamente una reserva. El socio puede solicitar el Hold explícitamente mediante placeHold.

## Qué sé que está roto y entrego igualmente

- **Reservas vencidas:** no hay una tarea automática que las revise. Aunque hayan pasado las 48 horas, una reserva puede seguir figurando como `ASSIGNED` hasta que alguien realiza una operación relacionada. Entonces se libera la copia o se ofrece al siguiente socio; mientras tanto, puede parecer retenida más tiempo del debido.
- **Multas de préstamos sin devolver:** la multa de ese préstamo solo se calcula y añade al saldo al devolverlo. Si un socio conserva un préstamo vencido, podría pedir otro o entrar en cola (si cumple las demás reglas) porque ese retraso aún no aumenta su deuda registrada. Renovar el préstamo vencido sí está bloqueado.
- **Concurrencia y fallos:** `synchronized (copies)` evita carreras solo entre servicios que comparten el mismo repositorio en una JVM. Además, guardar copia, préstamo y reserva exige varios `save`: si uno falla, pueden quedar datos contradictorios, por ejemplo una copia marcada como prestada sin su préstamo. Con PostgreSQL harían falta una transacción, bloqueo de filas y una restricción de préstamo activo único por copia.

## Siguientes 30 minutos

Primero probaría qué pasa si falla el guardado a mitad de un préstamo o una devolución, para que la copia y el préstamo no queden con estados contradictorios. Después decidiría cómo calcular la multa de un préstamo aún sin devolver sin cobrar dos veces el mismo retraso. Por último, explicaría cómo proteger estas operaciones con una transacción al pasar a una base de datos.

## Uso de IA

Pedí a la IA un primer análisis del PDF y borradores del modelo, las políticas y las pruebas: podía contrastar sus propuestas con el enunciado y ejecutar los tests. No acepté todo tal cual. Usó `0.20` en una prueba de `Money`, confundiendo un importe genérico con la tarifa de multa; al revisar que esa tarifa debía poder cambiar, corregí la prueba y dejé la regla en `FinePolicy`. Otra prueba intentaba alcanzar el límite prestando tres copias del mismo título a un socio: falló al aplicar la regla de un solo préstamo por título, y la cambié para usar tres títulos distintos. Al acordar que una deuda superior a 10 € también bloquease la cola, `mvn test` detectó una prueba antigua que aún la permitía; la corregí.
Acepté la configuración en memoria sin comprobar a fondo qué pasa si se actualiza desde varios hilos a la vez. Por ejemplo, un hilo podría actualizar la tarifa de multa y el umbral de bloqueo mientras otro procesa una devolución. Los valores individuales se guardan con mecanismos seguros para concurrencia, pero no garantizamos que varios cambios relacionados se vean como un único conjunto: una operación podría ver la tarifa nueva y el umbral anterior.
