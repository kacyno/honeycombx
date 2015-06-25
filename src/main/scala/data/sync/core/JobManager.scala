package data.sync.core

import java.util.Date
import java.util.concurrent.ConcurrentHashMap

import data.sync.common.ClientMessages.DBInfo
import data.sync.common.ClusterMessages._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, FileSystem}
import scala.collection.JavaConversions._

/**
 * 维护着Job及task,taskAttempt的相关信息
 * Created by hesiyuan on 15/6/23.
 */
case class JobInfo(jobId: String,
                   priority: Int,
                   submitTime: Long,
                   targetDir: String,
                   info: Array[DBInfo],
                   appendTasks: scala.collection.mutable.Set[TaskInfo],
                   runningTasks: scala.collection.mutable.Set[TaskInfo],
                   finishedTasks: scala.collection.mutable.Set[TaskInfo],
                   var status: JobStatus = JobStatus.SUBMITED
                    )

object JobManager {
  val fs = FileSystem.get(new Configuration())
  val MAX_ATTEMPT = 3
  //taskAttemptId到taskAttempt的映射
  private val taskAttemptDic = new ConcurrentHashMap[String, TaskAttemptInfo]
  //taskId到taskAttempt映射
  private val taskDic = new ConcurrentHashMap[String, Array[TaskAttemptInfo]]
  //jobid到jobinfo的映射
  private val jobDic = new ConcurrentHashMap[String, JobInfo]
  //bee->taskAttempt
  private val bee2attempt = new ConcurrentHashMap[String, Set[String]]
  //taskAttempt->bee
  private val attempt2bee = new ConcurrentHashMap[String, String]
  //taskAttempt->report
  private val attempt2report = new ConcurrentHashMap[String, BeeAttemptReport]

  def updateAttempt(attempt: TaskAttemptInfo): Unit = {
    taskAttemptDic(attempt.attemptId) = attempt
  }

  def mapBeeAttempt(beeId: String, attemptId: String): Unit = {
    attempt2bee(attemptId) = beeId;
    var s = bee2attempt.getOrElse(beeId, Set[String]())
    s += attemptId
  }

  def removeBeeAttempt(beeId: String, attemptId: String): Unit = {
    attempt2bee -= attemptId
    var s = bee2attempt.getOrElse(beeId, Set[String]())
    s -= attemptId
  }

  def getAttempts(taskId: String): Array[TaskAttemptInfo] = {
    taskDic.getOrElse(taskId, Array[TaskAttemptInfo]())
  }

  def setAttempts(taskId: String, attempts: Array[TaskAttemptInfo]) {
    taskDic(taskId) = attempts
  }

  def getReport(attemptId: String): BeeAttemptReport = {
    attempt2report.getOrElse(attemptId, null)
  }

  def updateReport(report: BeeAttemptReport): Unit = {
    attempt2report(report.attemptId) = report
  }

  def removeReport(attemptId: String): Unit = {
    attempt2report -= attemptId
  }

  //对长时间没有状态更新的任务启动并行
  def checkTimeOut(): Boolean = JobManager.synchronized{
    var needAssign = false
    val now = new Date().getTime
    for (attemptId <- attempt2bee.keys()) {
      //两分钟没有汇报状态的将重新生成一个attempt并行执行
      if (now - attempt2report(attemptId).time > 2 * 60 * 1000&&attempt2report(attemptId).status!=TaskAttemptStatus.FAILED&&attempt2report(attemptId).status!=TaskAttemptStatus.FINSHED) {
        val task = taskAttemptDic(attemptId).taskDesc
        val job = jobDic(task.jobId)
        job.runningTasks -= task
        job.appendTasks += task
        needAssign = true
      }
    }
    needAssign
  }


  def getJob(jobId: String): JobInfo = {
    jobDic.getOrElse(jobId, null)
  }

  def addJob(job: JobInfo): Unit = {
    jobDic(job.jobId) = job
  }

  def processReport(beeId: String, report: BeeAttemptReport, queen: Queen): Unit = JobManager.synchronized{
    val apt = taskAttemptDic.getOrElse(report.attemptId, null)
    if (apt != null&&apt.taskDesc.status!=TaskStatus.FAILED&&apt.taskDesc.status!=TaskStatus.FINSHED) {//当task有确定结果时将不再接受新的汇报

      val ar = attempt2report.getOrElse(report.attemptId, null)

      if (ar == null || ar.readNum != report.readNum || ar.writeNum != report.writeNum || report.status != ar.status) //只对变更的汇报进行更新
        updateReport(report)

      apt.status = report.status

      val task = apt.taskDesc

      task.status = TaskStatus.RUNNING

      val job = jobDic(task.jobId)

      if (report.status == TaskAttemptStatus.FINSHED) {
        //如果有其它并行运行的attempt,干掉
        for(attempt2kill<-taskDic(task.taskId) if(attempt2kill.status==TaskAttemptStatus.RUNNING||attempt2kill.status==TaskAttemptStatus.STARTED)){
          BeeManager.getBee(attempt2bee(attempt2kill.attemptId)).sender ! StopAttempt(attempt2kill.attemptId)
        }
        removeBeeAttempt(beeId, report.attemptId)
        task.status = TaskStatus.FINSHED
        job.runningTasks -= task
        job.finishedTasks += task
        //job运行成功
        if (job.runningTasks.size == 0 && job.appendTasks.size == 0) {
          job.status = JobStatus.FINSHED
          commitJob(job.jobId)
        }
      } else if (report.status == TaskAttemptStatus.FAILED) {
        removeBeeAttempt(beeId, report.attemptId)
        job.runningTasks -= task
        if (taskDic(task.taskId).length == MAX_ATTEMPT
          &&taskDic(task.taskId).filter(_.status!=TaskAttemptStatus.FAILED).length==0) {//达到最大重试次数，并且已经执行过的attempt全是失败
          task.status = TaskStatus.FAILED
          killJob(job.jobId)
        } else {
          job.appendTasks += task
        }
      }
    }
  }

  def removeAttemptByBee(beeId: String, queen: Queen): Unit = {
    for (atpId <- bee2attempt(beeId)) {
      attempt2bee -= atpId
      val atp = taskAttemptDic(atpId)
      val task = atp.taskDesc
      val job = jobDic(atp.taskDesc.jobId)
      job.runningTasks -= task
      if (taskDic(task.taskId).length == MAX_ATTEMPT) {
        killJob(job.jobId)
      } else
        job.appendTasks += task
    }
    bee2attempt -= beeId
  }

  /*
   *Job成功运行完成，commit
   * 将job成功的taskAttempt文件移到目标目录下，删除无关的文件
   * 将job从调度队列里移除
   * 将job及相关信息从JobManager里移除
   */
  def commitJob(jobId: String): Unit = {
    val job = jobDic(jobId)
    if (job.status == JobStatus.FINSHED) {
      for (task <- job.finishedTasks) {
        for (atpId <- taskDic(task.taskId) if (taskAttemptDic(atpId.attemptId).status == TaskAttemptStatus.FINSHED)) {
          fs.rename(new Path(job.targetDir + "tmp/" + atpId.attemptId), new Path(job.targetDir + atpId.attemptId))
        }
      }
      FIFOScheduler.delJob(job)
      fs.delete(new Path(job.targetDir + "tmp/"), true) //失败的任务可能在删文件时还在操作，只删成功的
    }
    clearJob(jobId)
  }

  /*
   *Job出现错误，杀死job
   * 向所有相关的bee发送杀死job的请求，同时commitjob
   */
  def killJob(jobId: String): Unit = {
    val job = jobDic(jobId)
    job.status = JobStatus.FAILED
    FIFOScheduler.delJob(job)
    val bees = scala.collection.mutable.Set[String]()
    for (task <- job.runningTasks) {
      for (apt <- taskDic(task.taskId)) {
        if (apt.status != TaskAttemptStatus.FINSHED)
          apt.status = TaskAttemptStatus.KILLED
        if (attempt2bee.contains(apt.attemptId))
          bees += attempt2bee(apt.attemptId)
      }
    }
    bees.foreach(BeeManager.getBee(_).sender ! KillJob(jobId))
    commitJob(jobId)
  }

  /*
   * 清理job相关数据,并将其保存在文件中
   */
  def clearJob(jobId: String): Unit = {
    //将历史保存
    JobHistory.dumpMemJob(jobId)
    //清理数据
    val job = jobDic(jobId)

    val tasks = job.appendTasks ++ job.runningTasks ++ job.finishedTasks
    for (task <- tasks) {
      for (attempt <- taskDic(task.taskId)) {
        removeBeeAttempt(attempt2bee(attempt.attemptId), attempt.attemptId)
        attempt2report -= attempt.attemptId
        taskAttemptDic -= attempt.attemptId
      }
      taskDic -= task.taskId
    }
    jobDic -= jobId
  }
}