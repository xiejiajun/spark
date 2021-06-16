### Spark任务提交全流程分析

#### spark-submit: xiejj/branch-3.1
- spark-submit -> spark-class org.apache.spark.deploy.SparkSubmit -> org.apache.spark.launcher.Main
-> Main.main -> Main.buildCommand -> SparkSubmitCommandBuilder#buildCommand 
-> SparkSubmitCommandBuilder#buildSparkSubmitCommand -> SparkSubmit 
-> SparkSubmit.main -> SparkSubmit.doSubmit(overwrite的那个) -> SparkSubmit#doSubmit
-> SparkSubmit#submit -> SparkSubmit#submit.doRunMain -> SparkSubmit#runMain
-> JavaMainApplication#start(Yarn Client模式)/ [YarnClusterApplication.start -> Client#run (Yarn Cluster模式)] -> 反射执行用户的main方法 -> 用户main方法中的rdd_action算子 -> SparkContext.runJob
-> DAGScheduler#runJob -> DAGScheduler#submitJob -> EventLoop#post(JobSubmitted) 
-> 将JobSubmitted事件放到EventLoop的eventQueue队列里面等待eventThread线程调用DAGSchedulerEventProcessLoop#onReceive方法处理
-.-> EventLoop.eventThread.run(该线程启动流程为：new SparkContext -> SparkContext.createTaskScheduler提交yarn client模式下用于启动和管理Executor生命周期的AM
-> SparkContext._dagScheduler -> new DAGScheduler -> eventProcessLoop.start -> org.apache.spark.util.EventLoop#start -> eventThread.start) -> DAGSchedulerEventProcessLoop#onReceive -> DAGSchedulerEventProcessLoop#doOnReceive
-> DAGScheduler#handleJobSubmitted -> DAGScheduler#createResultStage(RDD => Stage依赖树) 
-> DAGScheduler#submitStage -> DAGScheduler#submitMissingTasks 
-> 调用closureSerializer.serialize & JavaUtils.bufferToArray 序列化Stage对象并通过SparkContext.broadcast广播到所有Executor
-> 将Stage转换成ShuffleMapTask/ResultTask -> TaskSchedulerImpl#submitTasks提交TaskSet 
-> CoarseGrainedSchedulerBackend.reviveOffers发送触发作业调度的ReviveOffers事件 -> NettyRpcEndpointRef#send 
-> CoarseGrainedSchedulerBackend.DriverEndpoint#receive -> CoarseGrainedSchedulerBackend.DriverEndpoint#makeOffers
-> TaskSchedulerImpl#resourceOffers -> TaskSchedulerImpl#resourceOfferSingleTaskSet 
-> TaskSetManager#resourceOffer:将TaskSet解析成TaskDescription 
-> CoarseGrainedSchedulerBackend.DriverEndpoint#launchTasks
-> NettyRpcEndpointRef#send(LaunchTask)通过Netty Rpc调用发送包含Task信息的LaunchTask事件到对应的executor
-> RPC -> ... -> CoarseGrainedExecutorBackend#receive -> Executor#launchTask启动Task
-> TaskRunner#run -> Task#run -> ShuffleMapTask/ResultTask#runTask这样就把Task运行起来了
-> ... -> RDD#iterator: RDD封装的数据处理逻辑执行入口 -> ... -> RDD#computeOrReadCheckpoint
-> RDD#compute: 具体RDD实现的compute方法，里面会调用用户编写的逻辑, 详细分析请看MapPartitionsRDD.compute,其他RDD实现也一样

- [可以说new SparkContext是Spark应用的入口](https://github.com/xiejiajun/spark-learn/blob/master/src/main/scala/com/jiajun/spark/core/rdd/actions/RDDActionExample.scala)
    - 只不过现在线程都通过SparkSession.getOrCreate等方法进行封装了，很少直接new SparkContext了

---
- Spark On Yarn Cluster模式： ... -> SparkSubmit#runMain -> YarnClusterApplication.start -> Client#run -> Client#submitApplication
  -> Client#createContainerLaunchContext:构建AM容器启动命令，指定ApplicationMaster为AM启动类，并通过--class在AM容器中指定Driver入口类 -> yarnClient.submitApplication ->
  -> ApplicationMaster.main -> ApplicationMaster.run -> ApplicationMaster.runDriver -> ApplicationMaster.startUserApplication -> mainMethod.invoke: 执行用户代码（启动Driver)
  ->Client#monitorApplication: 如果不是cluster模式或者WAIT_FOR_APP_COMPLETION为false才会执行到这个分支等待执行结果

---
- ShuffledRDD比较特殊，他是官方实现的用于Task之间数据交换的RDD， 它的compute方法实现了Task之间数据交换的逻辑：BlockStoreShuffleReader.read 
-> ShuffleBlockFetcherIterator.toCompletionIterator得到一个基于RPC的Iterator实现类CompletionIterator 
-> CompletionIterator获取数据的next方法流程如下：CompletionIterator.next -> ShuffleBlockFetcherIterator.next
-> ShuffleBlockFetcherIterator.fetchUpToMaxBytes -> send -> sendRequest
-> ExternalBlockStoreClient/NettyBlockTransferService.fetchBlocks -> ...
-> OneForOneBlockFetcher.start -> TransportClient.sendRpc -> ...
-> BlockFetchingListener.onBlockFetchSuccess -> 数据缓存到results队列
-> ShuffleBlockFetcherIterator.next中的result = results.take()读取到数据

---
- Shuffle数据拉取RPC调用的执行流程梳理: TransportClient.sendRpc -> Netty Rpc -> ExternalBlockHandler.handleMessage处理该请求

---
- Shuffle服务初始化流程： ExternalShuffleService#start -> TransportContext#createServer -> new TransportServer -> TransportServer#init

---
-> TransportContext#initializePipeline -> TransportContext#createChannelHandler -> new TransportChannelHandler
-> 主要初始化流程结束

---
- Shuffle服务处理请求的流程：TransportChannelHandler.channelRead0 -> TransportRequestHandler#handle -> TransportRequestHandler#processRpcRequest
-> NettyBlockRpcServer/ExternalBlockHandler#receive -> ExternalBlockHandler#handleMessage(外部Shuffle服务支持)/NettyBlockRpcServer#receive(内嵌的Shuffle服务)


---
### DataSet API分析
- DataSet API触发计算流程： 
    - 线路1：DataSet.reduce/foreachPartition/foreach -> DataSet.rdd.reduce(DataSet.rdd最终会触发DataSet构建RDD[InternalRow]并对RDD[InternalRow]进行一系列转换构建出基于RDD[InternalRow]的DAG，然后执行RDD[InternalRow].reduce触发计算)
    - 线路2：DataSet.xxx -> DataSet.collectFromPlan/count -> ... -> SparkPlan.executeCollect -> SparkPlan.getByteArrayRdd ->
      SparkPlan.execute(触发DataSet构建RDD[InternalRow]并对RDD[InternalRow]进行一系列转换构建出基于RDD[InternalRow]的DAG) -> RDD.collect触发计算
    - 线路3：DataSet.toLocalIterator -> SparkPlan.executeToIterator -> SparkPlan.getByteArrayRdd 
      -> SparkPlan.execute(触发DataSet构建RDD[InternalRow]并对RDD[InternalRow]进行一系列转换构建出基于RDD[InternalRow]的DAG)
      -> RDD.toLocalIterator触发计算