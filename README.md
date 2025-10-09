How to run:

1.  sbt "runMain net.kaduk.MainApp"
2.  printf "hello\n" | sbt -Dgrpc.port=6060 "runMain net.kaduk.HumanAgentClient"