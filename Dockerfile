FROM azul/zulu-openjdk:25-latest

LABEL "com.github.actions.name"="read Java properties"
LABEL "com.github.actions.description"="read Java properties file and return one or more as property values as plain text or JSON"
LABEL "repository"="https://github.com/freenet-actions/read-java-properties"
LABEL "homepage"="https://github.com/freenet-actions"
LABEL "maintainer"="Team MCBS Core <tp.sd.back.mcbs@freenet.ag>"

WORKDIR /action
COPY *.java .
RUN ["javac", "Action.java"]
ENTRYPOINT ["java", "--class-path", ".", "Action"]
