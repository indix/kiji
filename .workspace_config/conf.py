# Workspace global configuration file

# Ordered list of Maven repository URLs:
maven_repositories = [
    # Standard Maven artifacts:
    "http://repo.maven.apache.org/maven2",
    # "http://central.maven.org/maven2",  # Unsure if there is a difference from the Apache repo

    # Concurrent Cascading:
    "http://conjars.org/repo",

    # Reimann:
    "https://clojars.org/repo",

    # CDH (Hadoop, HBase, etc):
    "https://repository.cloudera.com/artifactory/cloudera-repos",

    # This repo hosts scopt.
    "https://oss.sonatype.org/content/repositories/public",
]

# Supported Java versions:
java_versions = {"1.8.0_121", "1.7.0_80"}

# Supported Python versions:
python_versions = {"3.5.2", "2.7.12"}


avro_version = "1.7.6"
scala_version = "2.11.8"
checkstyle_version = "6.1.1"
scalastyle_version = "0.6.0"


# Version of the Java source code:
java_source_version = "1.8"

# Version of the JVM bytecode to produce (from Java and Scala source code):
jvm_target_version = "1.8"
