# Instalación

Java, maven y OpenJFX (porque se necesita el jfxrt.jar)
```
sudo apt-get install openjdk-8-jdk
sudo apt-get install openjfx
sudo apt-get install maven
sudo find / -iname jfxrt.jar # buscar el path
mvn install:install-file -Dfile="/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/ext/jfxrt.jar" -DgroupId=com.oracle.javafx -DartifactId=javafx -Dversion=2.1 -Dpackaging=jar
```

Para instalar el modelo, ver `model/README.md`.

# Ejecución
Usar `mvn spring-boot:run` en el directorio principal. El backend escucha en `0.0.0.0:8080`.

