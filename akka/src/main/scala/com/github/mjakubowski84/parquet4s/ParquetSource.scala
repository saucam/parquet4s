package com.github.mjakubowski84.parquet4s

import akka.NotUsed
import akka.stream.scaladsl.Source
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.filter2.compat.FilterCompat
import org.apache.parquet.hadoop.ParquetReader as HadoopParquetReader
import org.apache.parquet.schema.MessageType
import org.slf4j.{Logger, LoggerFactory}

object ParquetSource extends IOOps {

  /**
    * Factory of builders of Parquet readers.
    */
  trait FromParquet {
    /**
      * Creates [[Builder]] of Parquet reader for documents of type <i>T</i>.
      */
    def as[T: ParquetRecordDecoder]: Builder[T]
    /**
      * Creates [[Builder]] of Parquet reader for <i>projected</i> documents of type <i>T</i>.
      * Due to projection reader does not attempt to read all existing columns of the file but applies enforced
      * projection schema.
      */
    def projectedAs[T: ParquetRecordDecoder: ParquetSchemaResolver]: Builder[T]
    /**
      * Creates [[Builder]] of Parquet reader of generic records.
      */
    def generic: Builder[RowParquetRecord]
    /**
      * Creates [[Builder]] of Parquet reader of <i>projected</i> generic records.
      * Due to projection reader does not attempt to read all existing columns of the file but applies enforced
      * projection schema.
      */
    def projectedGeneric(projectedSchema: MessageType): Builder[RowParquetRecord]
  }

  private[parquet4s] object FromParquetImpl extends FromParquet {
    override def as[T: ParquetRecordDecoder]: Builder[T] = BuilderImpl()
    override def projectedAs[T: ParquetRecordDecoder: ParquetSchemaResolver]: Builder[T] = BuilderImpl(
      projectedSchemaResolverOpt = Option(implicitly[ParquetSchemaResolver[T]])
    )
    override def generic: Builder[RowParquetRecord] = BuilderImpl()
    override def projectedGeneric(projectedSchema: MessageType): Builder[RowParquetRecord] = BuilderImpl[RowParquetRecord](
      projectedSchemaResolverOpt = Option(RowParquetRecord.genericParquetSchemaResolver(projectedSchema))
    )
  }

  /**
   * Builds instance of Parquet [[akka.stream.scaladsl.Source]]
   * @tparam T type of data generated by the source.
   */
  trait Builder[T] {
    /**
     * @param options configuration of how Parquet files should be read
     */
    def options(options: ParquetReader.Options): Builder[T]
    /**
     * @param filter optional before-read filter; no filtering is applied by default; check [[Filter]] for more details
     */
    def filter(filter: Filter): Builder[T]
    /**
     * @param path [[Path]] to Parquet files, e.g.: {{{ Path("file:///data/users") }}}
     * @return final [[akka.stream.scaladsl.Source]]
     */
    def read(path: Path): Source[T, NotUsed]
  }

  private case class BuilderImpl[T: ParquetRecordDecoder](options: ParquetReader.Options = ParquetReader.Options(),
                                                          filter: Filter = Filter.noopFilter,
                                                          projectedSchemaResolverOpt: Option[ParquetSchemaResolver[T]] = None
                                                          ) extends Builder[T] {
    override def options(options: ParquetReader.Options): Builder[T] =
      this.copy(options = options)

    override def filter(filter: Filter) : Builder[T] =
      this.copy(filter = filter)

    override def read(path: Path): Source[T, NotUsed] =
      ParquetSource.apply(path, options, filter, projectedSchemaResolverOpt)

  }

  override protected lazy val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private def apply[T: ParquetRecordDecoder](path: Path,
                                             options: ParquetReader.Options,
                                             filter: Filter,
                                             projectedSchemaResolverOpt: Option[ParquetSchemaResolver[T]]
                                            ): Source[T, NotUsed] = {
    val valueCodecConfiguration = ValueCodecConfiguration(options)
    val hadoopConf = options.hadoopConf

    findPartitionedPaths(path, hadoopConf).fold(
      Source.failed,
      partitionedDirectory => {
        val projectedSchemaOpt = projectedSchemaResolverOpt
          .map(implicit resolver => ParquetSchemaResolver.resolveSchema(partitionedDirectory.schema))
        val sources = PartitionFilter
          .filter(filter, valueCodecConfiguration, partitionedDirectory)
          .map(createSource[T](valueCodecConfiguration, hadoopConf, projectedSchemaOpt).tupled)

        if (sources.isEmpty) Source.empty
        else sources.reduceLeft(_.concat(_))
      }
    )
  }

  private def createSource[T: ParquetRecordDecoder](valueCodecConfiguration: ValueCodecConfiguration,
                                                    hadoopConf: Configuration,
                                                    projectedSchemaOpt: Option[MessageType]
                                                   ):
                                                   (FilterCompat.Filter, PartitionedPath) => Source[T, NotUsed] = {
    (filterCompat, partitionedPath) =>
      def decode(record: RowParquetRecord): T = ParquetRecordDecoder.decode[T](record, valueCodecConfiguration)

      Source.unfoldResource[RowParquetRecord, HadoopParquetReader[RowParquetRecord]](
        create = () => createReader(hadoopConf, filterCompat, partitionedPath, projectedSchemaOpt),
        read = reader => Option(reader.read()),
        close = _.close()
      )
        .map(setPartitionValues(partitionedPath))
        .map(decode)
  }

  private def setPartitionValues[T](partitionedPath: PartitionedPath)(record: RowParquetRecord) =
    partitionedPath.partitions.foldLeft(record) { case (currentRecord, (columnPath, value)) =>
      currentRecord.updated(columnPath, BinaryValue(value))
    }

  private def createReader(hadoopConf: Configuration,
                           filterCompat: FilterCompat.Filter,
                           partitionedPath: PartitionedPath,
                           projectedSchemaOpt: Option[MessageType]
                          ): HadoopParquetReader[RowParquetRecord] =
    HadoopParquetReader
      .builder[RowParquetRecord](new ParquetReadSupport(projectedSchemaOpt), partitionedPath.path.toHadoop)
      .withConf(hadoopConf)
      .withFilter(filterCompat)
      .build()

}
