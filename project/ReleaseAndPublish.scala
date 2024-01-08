import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbt.Keys._
import sbt.{ Def, _ }

/**
 * 管理Jar包的对外发布，包含两部分内容：
 * - Publish 将jar包发布到maven仓库中
 * - Release 更新代码仓库的基线以及版本号
 *
 * 该配置如果要启用，建议增加到工程的setting中，追加即可
 *
 * 子工程单独发布：
 * - 那么保证每个子工程的根目录下有version.sbt
 * - version.sbt配置为"version := xxx" ，注意不要设置为"version in ThisBuild := xxx"，否则依然会使用统一的版本号
 * - releaseUseGlobalVersion := false，确保不使用全局统一的版本号，每个子工程使用自己定义的内容
 *
 * @note 目前暂不考虑使用sub project单独发布，因为这带给业务维护的巨大复杂性：
 *       - 子工程很多，每个子工程的版本号不一致，需要花费巨大精力考虑依赖关系和兼容性
 *       - 作为公共库，应该对外保持一致性，如果使用sapp，那么sapp各个jar包的版本保持一致，那么业务只需要关注一个版本号即可。
 */
object ReleaseAndPublish {
  // NBA私有仓库地址，配置jar包发布仓库，基于内部私有的maven仓库，如果后续地址变更需要更新。
  private val privateReposAddress = "https://mirrors.tencent.com"

  // 版本发布相关的配置，包含发布的仓库，鉴权，发布内容等信息
  def getSettings: Seq[
    Def.Setting[_ >: Task[Option[Resolver]] with Task[Seq[Credentials]] with Boolean with Task[
      PublishConfiguration] with Seq[ReleaseStep] with Task[Seq[String]] with Task[String]]] =
    Seq(
      // release版本发布到nba-release目录下；snapshot版本发布到nba-snapshot目录下
      publishTo := {
        Some(
          "tencent-mirrors".at(
            getPrivateRepositoryAddress(!(ThisBuild / version).value.endsWith("SNAPSHOT"))))
      },
      //仓库的认证信息，按照规范，存在放个人目录下，不要配置在sbt文件中
      credentials += Credentials(Path.userHome / ".sbt" / ".credentials"),
      // 添加源代码路径
      Compile / scalacOptions ++= {
        Seq("-sourcepath", (LocalRootProject / baseDirectory).value.getAbsolutePath)
      },
      // 允许sbt覆盖发布 jar 包，至于远程仓库是否可以覆盖，取决于远程仓库的权限配置，远程仓库无权限无法覆盖
      publishConfiguration := publishConfiguration.value.withOverwrite(true),
      releaseTagName := getReleaseTagName.value,
      releaseProcess := Seq[ReleaseStep](
        checkSnapshotDependencies, // 如果禁止依赖库中有snapshot，需要添加该项
        inquireVersions, // 版本获取
        runClean, // 构建前清理本地缓存
        releaseStepCommandAndRemaining("^ test"), // 运行测试用例，检查是否通过
        setReleaseVersion, // 更新版本号
        commitReleaseVersion, // 提交新的版本号到仓库
        tagRelease,
        releaseStepCommandAndRemaining("^ publish"),
        setNextVersion, // 设置下一个新版本
        commitNextVersion, // 提交新版本号到仓库
        pushChanges),
      releaseUseGlobalVersion := true)

  /**
   * 设定git打tag的命名前缀
   */
  private def getReleaseTagName: Def.Initialize[String] =
    Def.setting {
      s"${name.value}_v${if (releaseUseGlobalVersion.value) (ThisBuild / version).value
      else version.value}"
    }

  /**
   * 获取atum私有maven仓库地址，包含命名和地址
   *
   * @param release true表示是稳定版本，否则表示使用snapshot
   */
  def getAtumPrivateRepository(release: Boolean = true): MavenRepository = {
    val repoName = if (release) "release" else "snapshot"
    s"atum-$repoName".at(getPrivateRepositoryAddress(release))
  }

  /**
   * 获取本地仓库地址，注意仅仅是地址，不包含命名。release 和 snapshot 保存在不同repository 中。
   * [[https://stackoverflow.com/questions/275555/maven-snapshot-repository-vs-release-repository]]
   *
   * @param release true表示使用稳定版本，否则表示是使用snapshot
   */
  private def getPrivateRepositoryAddress(release: Boolean): String = {
    val repoName = if (release) "release" else "snapshot"
    privateReposAddress + s"/repository/maven/atum-$repoName"
  }
}
