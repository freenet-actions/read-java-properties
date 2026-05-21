FROM azul/zulu-openjdk:25.0.2-25.32@sha256:634efad18fb8fbf1dcc4c2d5a43c663435a77f078a912345f23f332e7abd27be

LABEL "com.github.actions.name"="read Java properties"
LABEL "com.github.actions.description"="read Java properties file and return one or more as property values as plain text or JSON"
LABEL "repository"="https://github.com/freenet-actions/read-java-properties"
LABEL "homepage"="https://github.com/freenet-actions"
LABEL "maintainer"="Team MCBS Core <tp.sd.back.mcbs@freenet.ag>"

WORKDIR /action
COPY *.java .
RUN ["chmod", "a-wx", "Action.java"]

# When re-building like this and using `java`, we may get an image with up-to-date Action.java,
# but outdated *.class. Don't know how GitHub actions handles this exactly, but this would break
# local Docker builds at least.
# # RUN ["javac", "Action.java"]
# # ENTRYPOINT ["java", "--class-path", "/action", "Action"]
# So instead:
ENTRYPOINT ["java", "Action.java"]
