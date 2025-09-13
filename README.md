Run server : 

`docker build -t java-workload . && docker run -p 9091:9091 -p 9092:9092 --cpus="2.0" --memory="4g" java-workload`
