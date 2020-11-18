#What it does

1. Reads the k8s config.
1. For each context, asks if it should be deleted (along with its associated cluster and user).
1. Displays updated k8s config.
1. Asks if the k8s config should be written back. If yes:
   1. Makes a backup of the original k8s config (optional).
   1. Writes the updated k8s config.
   
#Build

```
mvn clean package
```

#Run

Run with the default `<user home dir>/.kube/config`:
```
java -jar target/k8s-config-cleaner.jar
```

Run with another config file:
```
java -jar target/k8s-config-cleaner.jar /some/other/filename
```
