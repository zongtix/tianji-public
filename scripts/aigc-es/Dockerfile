ARG ES_BASE
FROM ${ES_BASE}
COPY --chown=1000:0 plugins/analysis-ik.zip /tmp/analysis-ik.zip
COPY --chown=1000:0 plugins/analysis-pinyin.zip /tmp/analysis-pinyin.zip
RUN bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && bin/elasticsearch-plugin install --batch file:///tmp/analysis-pinyin.zip \
    && rm /tmp/analysis-ik.zip /tmp/analysis-pinyin.zip
