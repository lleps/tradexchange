# Implementación
Modo: ./ live:<days>:
    Hacer el grafico de eventos de <days>
    Probar guardado y cargado del estado
Desincronización del grafico, es un problema?
Añadir un log en archivos cuando se implemente en producción.

# Estrategia
Se puede separar el código para ver si conviene vender o comprar.
Pero de un modo que resulte fácil hacer el chequeo.

Yo me imagino que, para cada "bloque" que se compra se quiere recuperar
luego más caro.

Pero, funcionando por una sola unidad. O sea, una compra y una venta. Aunque pueden
ir varias en paralelo.

Hay que simplificar tanto que sea muy sencillo añadir a la compra o venta, 
una bollinger band por ejemplo.


Pero eso, como que es una idea muy abstracta. Pero se trata de dar, a cada "trade" que seria
una compra y venta, un objeto, en el cual los checks quedarian totalmente claros...

Despues, si a mi me dan 20 monedas y yo las quiero dividir en
bloques de 5, en bloques de 3, o dependiendo de la sell/buy, se
cambia la cantidad por bloque, también sería otro tema.

La idea entonces, es:
- Introducir el concepto de un tiempo máximo.
- Crear clases para aclarar la toma de decisiones, basado en esta
  arquitectura que un "trade" es un evento que se usa para ganar plata,
  y necesita de su lógica.
  
  