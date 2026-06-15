FROM docker.elastic.co/elasticsearch/elasticsearch:8.15.0

# 构建时安装 IK 中文分词插件
RUN elasticsearch-plugin install --batch \
    https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.15.0.zip

# 复制自定义词典到 IK 配置目录
COPY custom.dic /usr/share/elasticsearch/config/analysis-ik/
COPY gov_term.dic /usr/share/elasticsearch/config/analysis-ik/
COPY stopword.dic /usr/share/elasticsearch/config/analysis-ik/
COPY IKAnalyzer.cfg.xml /usr/share/elasticsearch/config/analysis-ik/
