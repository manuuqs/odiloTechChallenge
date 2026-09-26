# Notas de entrega

Proyecto incremental en Java 17. Las tres decisiones siguientes definen el comportamiento previsto; los casos de uso aún no están implementados.

## Decisiones (3)

1. **Reservas.** Elegido: cola FIFO; una copia devuelta se asigna durante 48 horas y, si no se recoge, pasa al siguiente miembro. No se permiten reservas duplicadas del mismo título por miembro. Descartado: prioridades por nivel, volver al final de la cola y penalizaciones. Cambiaría si la biblioteca necesitase priorizar colectivos o sancionar reservas no recogidas.
2. **Multas y bloqueos.** Elegido: una deuda **superior** a 10,00 € impide nuevos préstamos y renovaciones, pero permite devoluciones y entrar en la cola. La multa inicial es 0,20 € por día de retraso. Descartado: bloquear todas las operaciones. Cambiaría si negocio exigiera impedir también reservas o definir un umbral distinto; importes y umbral se consultarán desde políticas configurables.
3. **Niveles y fechas.** Elegido: políticas por nivel sustituibles en ejecución; `Clock` e `Instant` (UTC) para calcular plazos; cambios posteriores no alteran vencimientos ya concedidos. Descartado: números dispersos en condicionales, usar `now()` directamente y modificar préstamos retroactivamente. Cambiaría si las reglas debieran ser retroactivas o el negocio fijara el día de vencimiento según la hora local de cada biblioteca.

## Fuera de alcance

API REST, interfaz gráfica, autenticación y persistencia entre reinicios. Un proveedor externo de configuración queda para una evolución posterior.

## Incompleto conocido

Están modelados `Title`, `Copy`, `Member`, `Loan` y `Hold`; el vencimiento y la ventana de recogida se calculan con políticas configurables. `LibraryService` sigue vacío: aún no hay asignación de copias, cola FIFO, renovación, cálculo de multas ni protección de la última copia frente a concurrencia. Para varias instancias, esa asignación necesitaría una transacción con bloqueo en PostgreSQL.

## Uso de IA

Delegué el análisis inicial del PDF, el esqueleto Maven/Java 17 y propuestas de tipos, políticas y tests para acelerar el arranque y detectar requisitos omitidos. Un resultado equivocado a nivel de diseño fue usar `0.20` (la multa del enunciado) como ejemplo en un test de `Money`: sugería que el valor monetario conocía esa regla. Lo detectamos al contrastarlo con el requisito de cambiar importes sin despliegue; cambiamos el test a importes genéricos y trasladamos la regla a `FinePolicy`. Verifiqué la compilación y los tests con `mvn test`, pero acepté provisionalmente el proveedor en memoria sin verificar aún su integración con `LibraryService` ni su comportamiento bajo concurrencia.
