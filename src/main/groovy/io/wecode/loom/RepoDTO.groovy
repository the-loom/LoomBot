package io.wecode.loom

class RepoDTO {
    def repo

    def toMap() {
        return [
                user: repo.owner.login,
                name: repo.name,
                git_url: repo.git_url,
                parent: repo.parent ? repo.parent.owner.login : null,
                avatar_url: repo.owner.avatar_url,
                description: repo.description
        ]
    }
}
