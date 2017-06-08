Para decidir que accion realizar, se puede crear una lista de Voters que según diferentes
criterios, deciden que acción es conveniente realizar. Pueden haber voters basados en datos
del grafico de 24 horas, otros basados en datos de semanas (que van a sugerir mas $), otros
en porcentajes, y en los montos. Se desacopla las decisiones, que es la lógica más importante, 
y se puede tener clara y precisa. Cada voter tendria desiciones y peso de las mismas. El modulo
principal, puede determinar el riesgo de las desiciones, basado en el nivel de coincidencia de los voters.
Los voters decidirian monto? O seria segun el nivel de seguridad que dan, decidido por modulo principal?

Otro modulo encargado de dibujar la información, el JavaFx con graficos y puntos.

Se puede abstraer el acceso a los datos, para que los Voters puedan acceder a información historica más sencillamente.

Se debe generar una arquitectura clara, capaz de ejecutar backtests y analizar las desiciones del algoritmo.