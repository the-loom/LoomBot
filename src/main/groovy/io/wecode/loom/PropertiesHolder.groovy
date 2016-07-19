package io.wecode.loom

@Singleton
class PropertiesHolder {
    Properties properties = new Properties()

    public load() {
        File propertiesFile = new File('.env')
        propertiesFile.withInputStream {
            properties.load(it)
        }
    }
}
